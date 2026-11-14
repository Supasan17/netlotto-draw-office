package lk.netlotto.server;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints the contents of the database as text tables. Works while the server is running.
 *
 * <p>Usage: {@code java -jar drawoffice-server.jar --report}
 */
final class DbReport {

    private DbReport() {
    }

    static void print(Path folder) throws SQLException {
        String file = folder.toAbsolutePath().resolve("drawoffice").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:file:" + file + ";AUTO_SERVER=TRUE;IFEXISTS=TRUE", "drawoffice", "drawoffice")) {
            table(c, "Accounts (only a salted PBKDF2 hash of each password is stored)",
                    "SELECT id, username, LEFT(password_hash, 34) || '...' AS password_hash, balance,"
                            + " FORMATDATETIME(created_at, 'yyyy-MM-dd HH:mm') AS opened FROM accounts ORDER BY id");
            table(c, "Latest 15 tickets",
                    "SELECT t.id, a.username, t.draw_no, t.numbers, t.status, t.matches, t.prize"
                            + " FROM tickets t JOIN accounts a ON a.id = t.account_id ORDER BY t.id DESC LIMIT 15");
            table(c, "Latest 15 statement entries",
                    "SELECT l.id, a.username, l.entry_type, l.amount, l.balance_after, l.reference"
                            + " FROM ledger l JOIN accounts a ON a.id = l.account_id ORDER BY l.id DESC LIMIT 15");
            table(c, "Last 5 draws",
                    "SELECT draw_no, numbers, tickets_sold, winners, jackpot_won,"
                            + " FORMATDATETIME(drawn_at, 'yyyy-MM-dd HH:mm:ss') AS drawn_at FROM draws ORDER BY draw_no DESC LIMIT 5");
            table(c, "Check: every balance equals the sum of its statement entries",
                    "SELECT a.username, a.balance, COALESCE(SUM(l.amount), 0) AS ledger_total,"
                            + " CASE WHEN a.balance = COALESCE(SUM(l.amount), 0) THEN 'OK' ELSE 'MISMATCH' END AS result"
                            + " FROM accounts a LEFT JOIN ledger l ON l.account_id = a.id GROUP BY a.id, a.username, a.balance ORDER BY a.id");
        }
    }

    private static void table(Connection c, String title, String sql) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        String[] head;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            head = new String[md.getColumnCount()];
            for (int i = 0; i < head.length; i++) {
                head[i] = md.getColumnLabel(i + 1).toLowerCase();
            }
            while (rs.next()) {
                String[] r = new String[head.length];
                for (int i = 0; i < r.length; i++) {
                    r[i] = String.valueOf(rs.getObject(i + 1));
                }
                rows.add(r);
            }
        }
        int[] w = new int[head.length];
        for (int i = 0; i < head.length; i++) {
            w[i] = head[i].length();
            for (String[] r : rows) {
                w[i] = Math.max(w[i], r[i].length());
            }
        }
        System.out.println();
        System.out.println("== " + title);
        System.out.println(line(head, w));
        StringBuilder rule = new StringBuilder();
        for (int x : w) {
            rule.append("-".repeat(x)).append("  ");
        }
        System.out.println(rule.toString().stripTrailing());
        for (String[] r : rows) {
            System.out.println(line(r, w));
        }
        if (rows.isEmpty()) {
            System.out.println("(none)");
        }
    }

    private static String line(String[] cells, int[] w) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            sb.append(String.format("%-" + w[i] + "s  ", cells[i]));
        }
        return sb.toString().stripTrailing();
    }
}
