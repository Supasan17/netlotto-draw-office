package lk.netlotto.server;

import lk.netlotto.core.Check;
import lk.netlotto.core.Frames;
import lk.netlotto.core.Message;
import lk.netlotto.core.Rules;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts a real server on a free port and talks to it over TCP like several desks would:
 * accounts and sign-in rules, purchases, simultaneous buyers of the same combination, quick picks,
 * a draw with live result events, the statement, sign-in lockout, and a restart with refunds.
 */
public final class ServerIntegrationTest {

    /** A bare protocol client: one reader thread puts every frame into a queue. */
    static final class TestDesk implements AutoCloseable {
        private final Socket socket;
        private final BufferedWriter out;
        private final BlockingQueue<Message> frames = new LinkedBlockingQueue<>();
        private final List<Message> events = new ArrayList<>();
        private final AtomicInteger refs = new AtomicInteger();

        TestDesk(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Thread t = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        frames.add(Message.decode(line));
                    }
                } catch (IOException ignored) {
                    // closed
                }
                frames.add(Message.of("EOF"));
            });
            t.setDaemon(true);
            t.start();
        }

        void send(String type, Object... kv) throws IOException {
            Object[] all = new Object[kv.length + 2];
            all[0] = "ref";
            all[1] = refs.incrementAndGet();
            System.arraycopy(kv, 0, all, 2, kv.length);
            synchronized (out) {
                out.write(Message.of(type, all).encode());
                out.write('\n');
                out.flush();
            }
        }

        /** Sends a request and returns its OK/FAIL; ROW frames go into rows, events into events. */
        Message request(List<Message> rows, String type, Object... kv) throws Exception {
            send(type, kv);
            String ref = String.valueOf(refs.get());
            while (true) {
                Message m = frames.poll(10, TimeUnit.SECONDS);
                if (m == null || m.type().equals("EOF")) {
                    return Message.of("EOF");
                }
                if (ref.equals(m.get("ref"))) {
                    if (m.type().equals(Frames.ROW)) {
                        if (rows != null) {
                            rows.add(m);
                        }
                        continue;
                    }
                    return m;
                }
                synchronized (events) {
                    events.add(m);
                }
            }
        }

        Message request(String type, Object... kv) throws Exception {
            return request(null, type, kv);
        }

        /** Waits for an event of the given type (looking at events already received first). */
        Message awaitEvent(String type, long seconds) throws InterruptedException {
            synchronized (events) {
                for (Message m : events) {
                    if (m.type().equals(type)) {
                        events.remove(m);
                        return m;
                    }
                }
            }
            long end = System.currentTimeMillis() + seconds * 1000;
            while (System.currentTimeMillis() < end) {
                Message m = frames.poll(200, TimeUnit.MILLISECONDS);
                if (m != null && m.type().equals(type)) {
                    return m;
                }
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("drawoffice-it");
        DrawServer server = DrawServer.launch(data, 0, 120);
        int port = server.port();
        String numbers = "5,11,17,23,29,35";

        try (TestDesk alice = new TestDesk(port); TestDesk bob = new TestDesk(port); TestDesk other = new TestDesk(port)) {
            Check.equal(Frames.HELLO, alice.awaitEvent(Frames.HELLO, 5).type(), "server greets a new connection");

            Message r = alice.request(Frames.BUY, "numbers", numbers);
            Check.equal(Frames.E_AUTH, r.get("code"), "buying before signing in is refused");

            r = alice.request(Frames.SIGN_UP, "user", "alice", "password", "short");
            Check.equal(Frames.E_INVALID, r.get("code"), "weak password is refused at sign-up");
            r = alice.request(Frames.SIGN_UP, "user", "alice", "password", "alice2026");
            Check.equal(Frames.OK, r.type(), "alice opens an account");
            Check.equal(Rules.OPENING_BALANCE, r.getInt("balance", -1), "new account gets the opening balance");
            r = bob.request(Frames.SIGN_UP, "user", "Alice", "password", "other2026");
            Check.equal(Frames.E_EXISTS, r.get("code"), "username already taken (ignoring case) is refused");
            r = bob.request(Frames.SIGN_UP, "user", "bob", "password", "bob2026x");
            Check.equal(Frames.OK, r.type(), "bob opens an account");

            r = other.request(Frames.SIGN_IN, "user", "bob", "password", "wrong2026");
            Check.equal(Frames.E_AUTH, r.get("code"), "wrong password is refused");
            r = other.request(Frames.SIGN_IN, "user", "nobody", "password", "wrong2026");
            Check.equal("The username or password is incorrect.", r.get("message"),
                    "unknown username gets the same message as a wrong password");
            r = other.request(Frames.SIGN_IN, "user", "ALICE", "password", "alice2026");
            Check.equal(Frames.E_IN_USE, r.get("code"), "an account cannot be signed in on two desks");

            Message clock = alice.awaitEvent(Frames.CLOCK, 3);
            Check.that(clock != null && clock.getInt("secs", 0) > 0, "signed-in desk receives the live draw clock");

            r = alice.request(Frames.BUY, "numbers", "35,29,23,17,11,5");
            Check.equal(Frames.OK, r.type(), "alice buys a ticket");
            Check.equal(Rules.OPENING_BALANCE - Rules.TICKET_PRICE, r.getInt("balance", -1), "alice is charged once");
            r = bob.request(Frames.BUY, "numbers", numbers);
            Check.equal(Frames.E_TAKEN, r.get("code"), "bob cannot buy the combination alice owns");
            List<Message> rows = new ArrayList<>();
            bob.request(rows, Frames.STATEMENT);
            Check.equal(1, rows.size(), "bob was not charged for the refused ticket");

            r = bob.request(Frames.BUY, "numbers", "1,1,2,3,4,5");
            Check.equal(Frames.E_INVALID, r.get("code"), "repeated number is refused by the server");

            // Ten desks send the same new combination at the same moment.
            int racers = 10;
            List<TestDesk> desks = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                TestDesk d = new TestDesk(port);
                d.request(Frames.SIGN_UP, "user", "racer" + i, "password", "racer2026");
                desks.add(d);
            }
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            List<Future<Message>> answers = new ArrayList<>();
            for (TestDesk d : desks) {
                answers.add(pool.submit(() -> {
                    go.await();
                    return d.request(Frames.BUY, "numbers", "2,8,14,20,26,32");
                }));
            }
            go.countDown();
            int ok = 0;
            int taken = 0;
            for (Future<Message> f : answers) {
                Message a = f.get(15, TimeUnit.SECONDS);
                if (Frames.OK.equals(a.type())) {
                    ok++;
                } else if (Frames.E_TAKEN.equals(a.get("code"))) {
                    taken++;
                }
            }
            pool.shutdown();
            Check.equal(1, ok, "10 simultaneous buyers of one combination: exactly one succeeds");
            Check.equal(racers - 1, taken, "the other 9 are told the combination is taken");
            for (TestDesk d : desks) {
                d.close();
            }

            rows.clear();
            r = bob.request(rows, Frames.QUICK_PICK, "count", 3);
            Check.equal(3, rows.size(), "quick pick returns three tickets");
            Check.equal(Rules.OPENING_BALANCE - 3 * Rules.TICKET_PRICE, r.getInt("balance", -1), "quick pick charges three tickets");
            r = bob.request(Frames.QUICK_PICK, "count", 9);
            Check.equal(Frames.E_INVALID, r.get("code"), "quick pick above the limit is refused");

            server.drawNow();
            Message result = alice.awaitEvent(Frames.RESULT, 5);
            Check.that(result != null && sixNumbers(result.get("numbers")), "every desk receives the draw result");
            Check.that(bob.awaitEvent(Frames.RESULT, 5) != null, "bob receives the same result");
            Message settled = alice.awaitEvent(Frames.SETTLED, 5);
            Check.that(settled != null, "alice is told how her ticket did");

            rows.clear();
            alice.request(rows, Frames.TICKETS);
            Check.that(rows.size() == 1 && !Frames.T_OPEN.equals(rows.get(0).get("status")), "alice's ticket is settled");
            rows.clear();
            Message st = alice.request(rows, Frames.STATEMENT);
            int sum = rows.stream().mapToInt(m -> m.getInt("amount", 0)).sum();
            Check.equal(st.getInt("balance", -1), sum, "alice's statement adds up to her balance");
            rows.clear();
            alice.request(rows, Frames.RESULTS);
            Check.equal(1, rows.size(), "results list has the draw");

            // Sign-in lockout after five failures from one address.
            Message last = null;
            for (int i = 0; i < 5; i++) {
                last = other.request(Frames.SIGN_IN, "user", "bob", "password", "guess" + i + "x");
            }
            Check.that(last != null && last.get("message").contains("locked"), "fifth failure locks sign-in");
            r = other.request(Frames.SIGN_IN, "user", "bob", "password", "bob2026x");
            Check.equal(Frames.E_LOCKED, r.get("code"), "even the right password waits during the lock");

            // A ticket left open when the server stops is refunded at the next start.
            r = alice.request(Frames.BUY, "numbers", "3,6,9,12,15,18");
            Check.equal(Frames.OK, r.type(), "alice buys a ticket for the next draw");
        }
        server.stop();

        DrawServer again = DrawServer.launch(data, 0, 120);
        try (TestDesk alice = new TestDesk(again.port())) {
            Message r = alice.request(Frames.SIGN_IN, "user", "alice", "password", "alice2026");
            Check.equal(Frames.OK, r.type(), "account survives a server restart");
            List<Message> rows = new ArrayList<>();
            alice.request(rows, Frames.TICKETS);
            Check.equal(Frames.T_REFUNDED, rows.get(0).get("status"), "the interrupted ticket is marked refunded");
            rows.clear();
            alice.request(rows, Frames.STATEMENT);
            Check.equal(Frames.L_REFUND, rows.get(0).get("type"), "the refund appears on the statement");
            Message clock = alice.awaitEvent(Frames.CLOCK, 3);
            Check.equal(3, clock.getInt("draw", 0), "draw numbering continues after the restart (draw 2 was interrupted)");
        } finally {
            again.stop();
        }
        Check.done();
    }

    private static boolean sixNumbers(String csv) {
        return csv != null && csv.split(",").length == Rules.PICK;
    }
}
