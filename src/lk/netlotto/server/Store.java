package lk.netlotto.server;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Rules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persistent storage in an embedded H2 database: accounts, tickets, draws and a ledger that records
 * every addition and deduction of points (the account statement).
 *
 * <p>All access goes through {@link #tx}, which holds the store's monitor and runs the work in one
 * database transaction. A purchase therefore either charges the account, records the ticket and
 * writes the statement entry together, or does none of them.
 */
final class Store implements AutoCloseable {

    static final class StoreException extends RuntimeException {
        StoreException(SQLException cause) {
            super(cause.getMessage(), cause);
        }
    }

    record Account(int id, String name) {
        static String key(String name) {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    record Login(Account account, String passwordHash) {}

    record Purchase(int ticketId, int balance, long boughtAt) {}

    /** One ticket's outcome in a draw. */
    record Payout(int ticketId, int accountId, int matches, int prize) {}

    /** A payout after it has been saved; balance is the account balance afterwards. */
    record Settled(int ticketId, int accountId, int matches, int prize, int balance) {}

    record TicketRow(int id, int drawNo, String numbers, String status, int matches, int prize, long boughtAt) {}

    record LedgerRow(int id, String type, int amount, int balanceAfter, String reference, String description,
                     long at) {}

    record DrawRow(int drawNo, String numbers, int ticketsSold, int winners, int jackpotWon, long drawnAt) {}

    private static final String UNIQUE_VIOLATION = "23505";

    @FunctionalInterface
    private interface Work<T> {
        T run() throws SQLException;
    }

    private final Connection db;

    Store(Path folder) throws SQLException {
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new SQLException("Cannot create the data folder " + folder, e);
        }
        String file = folder.toAbsolutePath().resolve("drawoffice").toString().replace('\\', '/');
        // AUTO_SERVER lets show-db.bat read the same file while the server is running.
        db = DriverManager.getConnection("jdbc:h2:file:" + file + ";AUTO_SERVER=TRUE", "drawoffice", "drawoffice");
        createTables();
    }

    private void createTables() throws SQLException {
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS accounts ("
                    + " id INT AUTO_INCREMENT PRIMARY KEY,"
                    + " username VARCHAR(20) NOT NULL,"
                    + " username_key VARCHAR(20) NOT NULL,"
                    + " password_hash VARCHAR(160) NOT NULL,"
                    + " balance INT NOT NULL,"
                    + " created_at TIMESTAMP NOT NULL,"
                    + " last_sign_in TIMESTAMP,"
                    + " CONSTRAINT accounts_unique_name UNIQUE (username_key),"
                    + " CONSTRAINT accounts_balance_not_negative CHECK (balance >= 0))");
            s.execute("CREATE TABLE IF NOT EXISTS draws ("
                    + " draw_no INT PRIMARY KEY,"
                    + " numbers VARCHAR(20) NOT NULL,"
                    + " tickets_sold INT NOT NULL,"
                    + " winners INT NOT NULL,"
                    + " jackpot_won INT NOT NULL,"
                    + " drawn_at TIMESTAMP NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS tickets ("
                    + " id INT AUTO_INCREMENT PRIMARY KEY,"
                    + " account_id INT NOT NULL REFERENCES accounts(id),"
                    + " draw_no INT NOT NULL,"
                    + " numbers VARCHAR(20) NOT NULL,"
                    + " status VARCHAR(10) NOT NULL,"
                    + " matches INT NOT NULL DEFAULT 0,"
                    + " prize INT NOT NULL DEFAULT 0,"
                    + " bought_at TIMESTAMP NOT NULL,"
                    // Second line of defence: the database refuses a repeated combination in one draw.
                    + " CONSTRAINT tickets_unique_combination UNIQUE (draw_no, numbers))");
            s.execute("CREATE INDEX IF NOT EXISTS tickets_by_account ON tickets(account_id, id)");
            s.execute("CREATE TABLE IF NOT EXISTS ledger ("
                    + " id INT AUTO_INCREMENT PRIMARY KEY,"
                    + " account_id INT NOT NULL REFERENCES accounts(id),"
                    + " entry_type VARCHAR(10) NOT NULL,"
                    + " amount INT NOT NULL,"
                    + " balance_after INT NOT NULL,"
                    + " reference VARCHAR(20) NOT NULL,"
                    + " description VARCHAR(80) NOT NULL,"
                    + " created_at TIMESTAMP NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS ledger_by_account ON ledger(account_id, id)");
            s.execute("CREATE TABLE IF NOT EXISTS system_state (name VARCHAR(32) PRIMARY KEY, val INT NOT NULL)");
        }
    }

    // ================================================================ accounts

    /** Creates an account with its opening balance. Returns null if the name is already registered. */
    Account createAccount(String name, String passwordHash, int opening) {
        return tx(() -> {
            int id;
            try (PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO accounts(username, username_key, password_hash, balance, created_at)"
                            + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, Account.key(name));
                ps.setString(3, passwordHash);
                ps.setInt(4, opening);
                ps.executeUpdate();
                id = generatedKey(ps);
            } catch (SQLException e) {
                if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    return null;
                }
                throw e;
            }
            addLedger(id, Frames.L_OPENING, opening, opening, "ACC-" + id, "Opening balance");
            return new Account(id, name);
        });
    }

    Login findLogin(String name) {
        return tx(() -> {
            try (PreparedStatement ps = db.prepareStatement(
                    "SELECT id, username, password_hash FROM accounts WHERE username_key = ?")) {
                ps.setString(1, Account.key(name));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Login(new Account(rs.getInt(1), rs.getString(2)), rs.getString(3)) : null;
                }
            }
        });
    }

    void markSignIn(int accountId) {
        tx(() -> {
            try (PreparedStatement ps = db.prepareStatement(
                    "UPDATE accounts SET last_sign_in = CURRENT_TIMESTAMP WHERE id = ?")) {
                ps.setInt(1, accountId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    int balance(int accountId) {
        return tx(() -> {
            try (PreparedStatement ps = db.prepareStatement("SELECT balance FROM accounts WHERE id = ?")) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    int accountCount() {
        return tx(() -> scalar("SELECT COUNT(*) FROM accounts"));
    }

    // ================================================================ tickets

    /**
     * Charges the account, records the ticket, writes the statement entry and grows the jackpot,
     * all in one transaction.
     *
     * @throws TicketRefused with {@link Frames#E_FUNDS} or {@link Frames#E_TAKEN}
     */
    Purchase purchase(int accountId, int drawNo, String numbers, int price, int jackpotShare) {
        return tx(() -> {
            int balance;
            try (PreparedStatement ps = db.prepareStatement("SELECT balance FROM accounts WHERE id = ? FOR UPDATE")) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    balance = rs.getInt(1);
                }
            }
            if (balance < price) {
                throw new TicketRefused(Frames.E_FUNDS,
                        "Insufficient balance. A ticket costs " + price + " points and you have " + balance + ".");
            }
            int ticketId;
            try (PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO tickets(account_id, draw_no, numbers, status, bought_at)"
                            + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, accountId);
                ps.setInt(2, drawNo);
                ps.setString(3, numbers);
                ps.setString(4, Frames.T_OPEN);
                ps.executeUpdate();
                ticketId = generatedKey(ps);
            } catch (SQLException e) {
                if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    throw TicketRefused.taken(numbers, drawNo);
                }
                throw e;
            }
            int after = balance - price;
            try (PreparedStatement ps = db.prepareStatement("UPDATE accounts SET balance = ? WHERE id = ?")) {
                ps.setInt(1, after);
                ps.setInt(2, accountId);
                ps.executeUpdate();
            }
            addLedger(accountId, Frames.L_PURCHASE, -price, after, Rules.ticketRef(ticketId),
                    "Ticket for draw " + Rules.drawRef(drawNo));
            addState("jackpot", jackpotShare);
            return new Purchase(ticketId, after, System.currentTimeMillis() / 1000);
        });
    }

    List<TicketRow> tickets(int accountId, int limit) {
        return tx(() -> {
            List<TicketRow> rows = new ArrayList<>();
            try (PreparedStatement ps = db.prepareStatement(
                    "SELECT id, draw_no, numbers, status, matches, prize, bought_at FROM tickets"
                            + " WHERE account_id = ? ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, accountId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new TicketRow(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getInt(6), rs.getTimestamp(7).getTime() / 1000));
                    }
                }
            }
            return rows;
        });
    }

    List<LedgerRow> statement(int accountId, int limit) {
        return tx(() -> {
            List<LedgerRow> rows = new ArrayList<>();
            try (PreparedStatement ps = db.prepareStatement(
                    "SELECT id, entry_type, amount, balance_after, reference, description, created_at FROM ledger"
                            + " WHERE account_id = ? ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, accountId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new LedgerRow(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                                rs.getString(5), rs.getString(6), rs.getTimestamp(7).getTime() / 1000));
                    }
                }
            }
            return rows;
        });
    }

    // ================================================================ draws

    /** Saves the draw, every ticket's outcome and every prize payment in one transaction. */
    List<Settled> settle(int drawNo, String numbers, List<Payout> payouts, int jackpotWon, int nextJackpot) {
        return tx(() -> {
            int winners = 0;
            for (Payout p : payouts) {
                if (p.prize() > 0) {
                    winners++;
                }
            }
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO draws(draw_no, numbers, tickets_sold,"
                    + " winners, jackpot_won, drawn_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                ps.setInt(1, drawNo);
                ps.setString(2, numbers);
                ps.setInt(3, payouts.size());
                ps.setInt(4, winners);
                ps.setInt(5, jackpotWon);
                ps.executeUpdate();
            }
            List<Settled> saved = new ArrayList<>();
            try (PreparedStatement ticket = db.prepareStatement(
                         "UPDATE tickets SET status = ?, matches = ?, prize = ? WHERE id = ?");
                 PreparedStatement credit = db.prepareStatement(
                         "UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
                for (Payout p : payouts) {
                    ticket.setString(1, p.prize() > 0 ? Frames.T_WON : Frames.T_NO_PRIZE);
                    ticket.setInt(2, p.matches());
                    ticket.setInt(3, p.prize());
                    ticket.setInt(4, p.ticketId());
                    ticket.executeUpdate();
                    int balance = -1;
                    if (p.prize() > 0) {
                        credit.setInt(1, p.prize());
                        credit.setInt(2, p.accountId());
                        credit.executeUpdate();
                        balance = balanceOf(p.accountId());
                        addLedger(p.accountId(), Frames.L_PRIZE, p.prize(), balance, Rules.ticketRef(p.ticketId()),
                                "Prize, " + p.matches() + " numbers matched in " + Rules.drawRef(drawNo));
                    }
                    saved.add(new Settled(p.ticketId(), p.accountId(), p.matches(), p.prize(), balance));
                }
            }
            setState("jackpot", nextJackpot);
            return saved;
        });
    }

    List<DrawRow> results(int limit) {
        return tx(() -> {
            List<DrawRow> rows = new ArrayList<>();
            try (PreparedStatement ps = db.prepareStatement("SELECT draw_no, numbers, tickets_sold, winners,"
                    + " jackpot_won, drawn_at FROM draws ORDER BY draw_no DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new DrawRow(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                                rs.getTimestamp(6).getTime() / 1000));
                    }
                }
            }
            return rows;
        });
    }

    /** The next unused draw number, so numbering continues after a restart. */
    int nextDrawNo() {
        return tx(() -> scalar("SELECT GREATEST(COALESCE((SELECT MAX(draw_no) FROM draws), 0),"
                + " COALESCE((SELECT MAX(draw_no) FROM tickets), 0)) + 1"));
    }

    /**
     * Tickets still open at startup belong to a draw that never ran because the server stopped.
     * Each one is refunded with its own statement entry. Returns how many were refunded.
     */
    int refundOpenTickets(int price) {
        return tx(() -> {
            List<int[]> open = new ArrayList<>();
            try (PreparedStatement ps = db.prepareStatement(
                    "SELECT id, account_id, draw_no FROM tickets WHERE status = ? ORDER BY id")) {
                ps.setString(1, Frames.T_OPEN);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        open.add(new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)});
                    }
                }
            }
            try (PreparedStatement ticket = db.prepareStatement("UPDATE tickets SET status = ? WHERE id = ?");
                 PreparedStatement credit = db.prepareStatement(
                         "UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
                for (int[] t : open) {
                    ticket.setString(1, Frames.T_REFUNDED);
                    ticket.setInt(2, t[0]);
                    ticket.executeUpdate();
                    credit.setInt(1, price);
                    credit.setInt(2, t[1]);
                    credit.executeUpdate();
                    addLedger(t[1], Frames.L_REFUND, price, balanceOf(t[1]), Rules.ticketRef(t[0]),
                            "Refund, draw " + Rules.drawRef(t[2]) + " did not take place");
                }
            }
            return open.size();
        });
    }

    /**
     * The saved jackpot. It is created with the seed value the first time, and raised to the seed if
     * a smaller value was saved by an older version (the jackpot never falls below its seed).
     */
    int jackpot(int seed) {
        return tx(() -> {
            try (PreparedStatement ps = db.prepareStatement("SELECT val FROM system_state WHERE name = 'jackpot'");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) >= seed) {
                    return rs.getInt(1);
                }
            }
            setState("jackpot", seed);
            return seed;
        });
    }

    // ================================================================ server window views

    /** Column names plus rows of text, for showing any query in a table. */
    record Table(List<String> columns, List<List<String>> rows) {}

    /** The tables the server window can show, by name, with the query behind each. */
    static final java.util.Map<String, String> VIEWS = new java.util.LinkedHashMap<>();

    static {
        VIEWS.put("Accounts", "SELECT a.id, a.username, a.balance,"
                + " (SELECT COUNT(*) FROM tickets t WHERE t.account_id = a.id) AS tickets,"
                + " (SELECT COALESCE(SUM(t.prize), 0) FROM tickets t WHERE t.account_id = a.id) AS prizes_won,"
                + " FORMATDATETIME(a.created_at, 'yyyy-MM-dd HH:mm') AS opened,"
                + " FORMATDATETIME(a.last_sign_in, 'yyyy-MM-dd HH:mm:ss') AS last_sign_in,"
                + " LEFT(a.password_hash, 40) || '...' AS password_hash FROM accounts a ORDER BY a.id");
        VIEWS.put("Tickets", "SELECT t.id, a.username, t.draw_no, t.numbers, t.status, t.matches, t.prize,"
                + " FORMATDATETIME(t.bought_at, 'yyyy-MM-dd HH:mm:ss') AS bought_at"
                + " FROM tickets t JOIN accounts a ON a.id = t.account_id ORDER BY t.id DESC LIMIT 500");
        VIEWS.put("Statement (ledger)", "SELECT l.id, a.username, l.entry_type, l.amount, l.balance_after, l.reference,"
                + " l.description, FORMATDATETIME(l.created_at, 'yyyy-MM-dd HH:mm:ss') AS created_at"
                + " FROM ledger l JOIN accounts a ON a.id = l.account_id ORDER BY l.id DESC LIMIT 500");
        VIEWS.put("Draws", "SELECT draw_no, numbers, tickets_sold, winners, jackpot_won,"
                + " FORMATDATETIME(drawn_at, 'yyyy-MM-dd HH:mm:ss') AS drawn_at FROM draws ORDER BY draw_no DESC LIMIT 500");
        VIEWS.put("System state", "SELECT name, val FROM system_state ORDER BY name");
        VIEWS.put("Balance check", "SELECT a.username, a.balance, COALESCE(SUM(l.amount), 0) AS statement_total,"
                + " CASE WHEN a.balance = COALESCE(SUM(l.amount), 0) THEN 'OK' ELSE 'MISMATCH' END AS result"
                + " FROM accounts a LEFT JOIN ledger l ON l.account_id = a.id"
                + " GROUP BY a.id, a.username, a.balance ORDER BY a.id");
    }

    /** Runs one of the fixed {@link #VIEWS}. Only these queries can run; nothing typed by a user. */
    Table view(String name) {
        String sql = VIEWS.get(name);
        if (sql == null) {
            throw new IllegalArgumentException("Unknown view " + name);
        }
        return tx(() -> {
            try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                List<String> cols = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    cols.add(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
                }
                List<List<String>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<String> r = new ArrayList<>();
                    for (int i = 1; i <= cols.size(); i++) {
                        Object o = rs.getObject(i);
                        r.add(o == null ? "" : String.valueOf(o));
                    }
                    rows.add(r);
                }
                return new Table(cols, rows);
            }
        });
    }

    // ================================================================ helpers

    private void addLedger(int accountId, String type, int amount, int balanceAfter, String ref, String text)
            throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO ledger(account_id, entry_type, amount,"
                + " balance_after, reference, description, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
            ps.setInt(1, accountId);
            ps.setString(2, type);
            ps.setInt(3, amount);
            ps.setInt(4, balanceAfter);
            ps.setString(5, ref);
            ps.setString(6, text);
            ps.executeUpdate();
        }
    }

    private int balanceOf(int accountId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT balance FROM accounts WHERE id = ?")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void setState(String name, int value) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("MERGE INTO system_state(name, val) KEY(name) VALUES (?, ?)")) {
            ps.setString(1, name);
            ps.setInt(2, value);
            ps.executeUpdate();
        }
    }

    private void addState(String name, int delta) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("UPDATE system_state SET val = val + ? WHERE name = ?")) {
            ps.setInt(1, delta);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private int scalar(String sql) throws SQLException {
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getInt(1);
        }
    }

    /** Runs the work as one transaction: commit when it returns, roll back when it throws. */
    private synchronized <T> T tx(Work<T> work) {
        try {
            db.setAutoCommit(false);
            T result = work.run();
            db.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new StoreException(e);
        } catch (RuntimeException e) {
            rollbackQuietly();
            throw e;
        } finally {
            try {
                db.setAutoCommit(true);
            } catch (SQLException ignored) {
                // The connection is broken; the next call reports it.
            }
        }
    }

    private void rollbackQuietly() {
        try {
            db.rollback();
        } catch (SQLException ignored) {
            // Nothing else to do.
        }
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException ignored) {
            // Shutting down.
        }
    }
}
