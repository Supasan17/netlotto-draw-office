package lk.netlotto.server;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Message;
import lk.netlotto.core.Rules;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One connected desk. Each session uses two threads from the server's pools:
 * <ul>
 *   <li>a <b>reader</b> ({@link #run}) that reads request frames and answers them, and</li>
 *   <li>a <b>writer</b> ({@link #writeLoop}) that drains this session's outbox onto the socket.</li>
 * </ul>
 * Other threads never touch the socket: they {@link #post} frames into the bounded outbox. A desk that
 * stops reading fills its outbox and is disconnected, so one slow computer cannot stall the draw clock
 * or the other terminals.
 */
final class Session implements Runnable {

    private static final String STOP = "\u0000stop";
    private static final int OUTBOX_CAPACITY = 1000;
    private static final int MAX_FRAME = 4096;

    private final Socket socket;
    private final DrawServer server;
    private final String peer;
    private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(OUTBOX_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Store.Account account;
    private final long connectedAt;

    long connectedAt() {
        return connectedAt;
    }

    Session(Socket socket, DrawServer server) {
        this.socket = socket;
        this.server = server;
        this.peer = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        this.connectedAt = System.currentTimeMillis() / 1000;
    }

    String peer() {
        return peer;
    }

    String host() {
        return socket.getInetAddress().getHostAddress();
    }

    Store.Account account() {
        return account;
    }

    // ================================================================ threads

    @Override
    public void run() {
        String reason = "connection closed by the desk";
        try {
            socket.setSoTimeout(Rules.IDLE_TIMEOUT_SECONDS * 1000);
            socket.setTcpNoDelay(true);
            server.startWriter(this::writeLoop);
            post(Message.of(Frames.HELLO, "server", "NetLotto Draw Office", "version", 2, "pick", Rules.PICK,
                    "highest", Rules.HIGHEST, "price", Rules.TICKET_PRICE, "maxQuick", Rules.MAX_QUICK_PICKS));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (!closed.get() && (line = in.readLine()) != null) {
                if (line.length() > MAX_FRAME) {
                    reason = "frame too long";
                    break;
                }
                if (!line.isBlank()) {
                    handle(line);
                }
            }
        } catch (SocketTimeoutException e) {
            reason = "no heartbeat for " + Rules.IDLE_TIMEOUT_SECONDS + " s";
        } catch (IOException e) {
            reason = "connection lost (" + e.getMessage() + ")";
        } finally {
            close(reason, false);
        }
    }

    private void writeLoop() {
        try {
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            while (true) {
                String frame = outbox.take();
                if (STOP.equals(frame)) {
                    out.flush();
                    break;
                }
                out.write(frame);
                out.write('\n');
                if (outbox.isEmpty()) {
                    out.flush();
                }
            }
        } catch (IOException | InterruptedException e) {
            // Socket gone or server stopping.
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    /** Queues a frame for this desk. Never blocks. */
    void post(Message frame) {
        if (closed.get()) {
            return;
        }
        if (!outbox.offer(frame.encode())) {
            Log.warn(peer + " is not reading its updates; disconnecting.");
            close("too slow to receive updates", false);
        }
    }

    /**
     * Ends the session. With {@code tell} the desk first receives a CLOSING frame with the reason.
     * Safe to call more than once and from any thread.
     */
    void close(String reason, boolean tell) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (tell) {
            outbox.offer(Message.of(Frames.CLOSING, "reason", reason).encode());
        } else {
            outbox.clear();
        }
        if (!outbox.offer(STOP)) {
            outbox.clear();
            outbox.offer(STOP);
        }
        server.ended(this, reason);
        if (!tell) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    // ================================================================ requests

    private void handle(String line) {
        Message req;
        try {
            req = Message.decode(line);
        } catch (IllegalArgumentException e) {
            post(Message.of(Frames.FAIL, "ref", 0, "code", Frames.E_INVALID, "message", "Malformed frame."));
            return;
        }
        String ref = req.get("ref", "0");
        try {
            switch (req.type()) {
                case Frames.PING:
                    post(Message.of(Frames.OK, "ref", ref));
                    break;
                case Frames.SIGN_IN:
                    signIn(ref, req.get("user", ""), req.get("password", ""));
                    break;
                case Frames.SIGN_UP:
                    signUp(ref, req.get("user", ""), req.get("password", ""));
                    break;
                case Frames.BUY:
                    buy(ref, req.get("numbers"));
                    break;
                case Frames.QUICK_PICK:
                    quickPick(ref, req.getInt("count", 1));
                    break;
                case Frames.TICKETS:
                    tickets(ref);
                    break;
                case Frames.STATEMENT:
                    statement(ref);
                    break;
                case Frames.RESULTS:
                    results(ref);
                    break;
                case Frames.SIGN_OUT:
                    post(Message.of(Frames.OK, "ref", ref));
                    close("signed out", true);
                    break;
                default:
                    fail(ref, Frames.E_UNKNOWN, "Unknown request " + req.type() + ".");
            }
        } catch (TicketRefused e) {
            fail(ref, e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            fail(ref, Frames.E_INVALID, e.getMessage());
        } catch (Store.StoreException e) {
            Log.error("Database error for " + peer + ": " + e.getMessage());
            fail(ref, Frames.E_SERVER, "The server could not complete the request. Please try again.");
        }
    }

    private void signIn(String ref, String user, String password) {
        if (account != null) {
            fail(ref, Frames.E_INVALID, "This connection is already signed in.");
            return;
        }
        int wait = server.lockedSeconds(host());
        if (wait > 0) {
            fail(ref, Frames.E_LOCKED, "Too many failed sign-in attempts. Try again in " + wait + " seconds.");
            return;
        }
        Store.Login login = Rules.validUsername(user) ? server.store().findLogin(user) : null;
        boolean ok;
        if (login != null) {
            ok = PasswordHasher.verify(password, login.passwordHash());
        } else {
            // Spend the same time as a real check, so response time does not reveal which names exist.
            PasswordHasher.verify(password, PasswordHasher.DECOY);
            ok = false;
        }
        if (!ok) {
            int left = server.recordFailure(host());
            Log.info("Failed sign-in for '" + user + "' from " + peer + ".");
            pause(500);
            fail(ref, Frames.E_AUTH, left > 0
                    ? "The username or password is incorrect."
                    : "The username or password is incorrect. Sign-in is locked for 60 seconds.");
            return;
        }
        server.clearFailures(host());
        open(ref, login.account(), "signed in");
    }

    private void signUp(String ref, String user, String password) {
        if (account != null) {
            fail(ref, Frames.E_INVALID, "This connection is already signed in.");
            return;
        }
        if (!Rules.validUsername(user)) {
            fail(ref, Frames.E_INVALID, Rules.USERNAME_RULE);
            return;
        }
        if (!Rules.validPassword(password)) {
            fail(ref, Frames.E_INVALID, Rules.PASSWORD_RULE);
            return;
        }
        Store.Account created = server.store().createAccount(user, PasswordHasher.hash(password), Rules.OPENING_BALANCE);
        if (created == null) {
            fail(ref, Frames.E_EXISTS, "The username " + user + " is already registered.");
            return;
        }
        Log.info("New account '" + user + "' opened from " + peer + ".");
        open(ref, created, "opened an account and signed in");
    }

    private void open(String ref, Store.Account acc, String what) {
        if (!server.claim(acc, this)) {
            fail(ref, Frames.E_IN_USE, "This account is already signed in on another computer.");
            return;
        }
        account = acc;
        server.store().markSignIn(acc.id());
        post(Message.of(Frames.OK, "ref", ref, "user", acc.name(), "account", acc.id(),
                "balance", server.store().balance(acc.id())));
        post(server.office().clockEvent());
        Message last = server.office().lastResult();
        if (last != null) {
            post(last);
        }
        Log.info(acc.name() + " " + what + " from " + peer + ".");
        server.broadcastNotice(acc.name() + " is now online.");
    }

    private void buy(String ref, String numbers) {
        Store.Account acc = requireAccount();
        DrawOffice.Sale s = server.office().buy(acc, Rules.parseSelection(numbers));
        post(Message.of(Frames.OK, "ref", ref, "ticket", s.ticketId(), "draw", s.drawNo(),
                "numbers", Rules.csv(Rules.fromKey(s.numbers())), "balance", s.balance(), "at", s.at()));
        Log.info(acc.name() + " bought " + Rules.ticketRef(s.ticketId()) + " " + s.numbers() + ".");
    }

    private void quickPick(String ref, int count) {
        Store.Account acc = requireAccount();
        List<DrawOffice.Sale> sales = server.office().quickPick(acc, count);
        int balance = 0;
        for (DrawOffice.Sale s : sales) {
            post(Message.of(Frames.ROW, "ref", ref, "ticket", s.ticketId(), "draw", s.drawNo(),
                    "numbers", Rules.csv(Rules.fromKey(s.numbers())), "at", s.at()));
            balance = s.balance();
        }
        post(Message.of(Frames.OK, "ref", ref, "count", sales.size(), "balance", balance));
        Log.info(acc.name() + " bought " + sales.size() + " quick pick ticket(s).");
    }

    private void tickets(String ref) {
        List<Store.TicketRow> rows = server.store().tickets(requireAccount().id(), Rules.LIST_LIMIT);
        for (Store.TicketRow t : rows) {
            post(Message.of(Frames.ROW, "ref", ref, "ticket", t.id(), "draw", t.drawNo(),
                    "numbers", Rules.csv(Rules.fromKey(t.numbers())), "status", t.status(), "matches", t.matches(),
                    "prize", t.prize(), "at", t.boughtAt()));
        }
        post(Message.of(Frames.OK, "ref", ref, "rows", rows.size()));
    }

    private void statement(String ref) {
        int id = requireAccount().id();
        List<Store.LedgerRow> rows = server.store().statement(id, Rules.LIST_LIMIT);
        for (Store.LedgerRow r : rows) {
            post(Message.of(Frames.ROW, "ref", ref, "entry", r.id(), "type", r.type(), "amount", r.amount(),
                    "balance", r.balanceAfter(), "reference", r.reference(), "text", r.description(), "at", r.at()));
        }
        post(Message.of(Frames.OK, "ref", ref, "rows", rows.size(), "balance", server.store().balance(id)));
    }

    private void results(String ref) {
        requireAccount();
        List<Store.DrawRow> rows = server.store().results(50);
        for (Store.DrawRow d : rows) {
            post(DrawOffice.resultEvent(d).with("ref", ref).withType(Frames.ROW));
        }
        post(Message.of(Frames.OK, "ref", ref, "rows", rows.size()));
    }

    private Store.Account requireAccount() {
        Store.Account acc = account;
        if (acc == null) {
            throw new TicketRefused(Frames.E_AUTH, "Sign in first.");
        }
        return acc;
    }

    private void fail(String ref, String code, String message) {
        post(Message.of(Frames.FAIL, "ref", ref, "code", code, "message", message));
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
