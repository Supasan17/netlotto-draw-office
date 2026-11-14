package lk.netlotto.desk;

import lk.netlotto.ui.*;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Message;
import lk.netlotto.core.Rules;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The desk's TCP connection to the server.
 *
 * <p>{@link #request} sends a frame with a new {@code ref} number and returns a future that completes
 * when the matching OK or FAIL arrives (with any ROW frames collected on the way). Requests never
 * block the Swing thread. Events (frames without a ref) are delivered to the {@link Listener} on the
 * Swing event thread; events that arrive before a listener is attached are kept and replayed.
 */
final class Link {

    interface Listener {
        void event(Message event);

        void closed(String reason);
    }

    /** The answer to one request. */
    record Reply(Message head, List<Message> rows) {
        boolean ok() {
            return Frames.OK.equals(head.type());
        }

        String code() {
            return head.get("code", "");
        }

        String message() {
            return head.get("message", "The server refused the request.");
        }
    }

    private static final class Pending {
        final CompletableFuture<Reply> future = new CompletableFuture<>();
        final List<Message> rows = new ArrayList<>();
    }

    private final Socket socket;
    private final String address;
    private final BufferedWriter out;
    private final BufferedReader in;
    private final AtomicInteger refs = new AtomicInteger();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final List<Message> backlog = new ArrayList<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat");
        t.setDaemon(true);
        return t;
    });
    private Listener listener;              // guarded by backlog
    private String closeReason;             // guarded by backlog
    private volatile boolean closedByUs;

    private Link(Socket socket, String address) throws IOException {
        this.socket = socket;
        this.address = address;
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(this::readLoop, "server-reader");
        reader.setDaemon(true);
        reader.start();
        heartbeat.scheduleAtFixedRate(() -> request(Frames.PING), Rules.HEARTBEAT_SECONDS, Rules.HEARTBEAT_SECONDS,
                TimeUnit.SECONDS);
    }

    /** Connects (blocking, at most 4 seconds). Call off the Swing thread. */
    static Link open(String host, int port) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setTcpNoDelay(true);
            return new Link(s, host + ":" + port);
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    String address() {
        return address;
    }

    boolean isOpen() {
        return !socket.isClosed();
    }

    /** Sends a request. The future fails with IOException if the connection is lost. */
    CompletableFuture<Reply> request(String type, Object... fields) {
        String ref = Integer.toString(refs.incrementAndGet());
        Pending p = new Pending();
        pending.put(ref, p);
        Object[] withRef = new Object[fields.length + 2];
        withRef[0] = "ref";
        withRef[1] = ref;
        System.arraycopy(fields, 0, withRef, 2, fields.length);
        try {
            synchronized (out) {
                out.write(Message.of(type, withRef).encode());
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            pending.remove(ref);
            p.future.completeExceptionally(e);
        }
        return p.future;
    }

    /** Starts delivering events to the listener on the Swing thread, including any held back. */
    void attach(Listener l) {
        List<Message> held;
        String reason;
        synchronized (backlog) {
            listener = l;
            held = new ArrayList<>(backlog);
            backlog.clear();
            reason = closeReason;
        }
        SwingUtilities.invokeLater(() -> {
            held.forEach(l::event);
            if (reason != null) {
                l.closed(reason);
            }
        });
    }

    void close() {
        closedByUs = true;
        heartbeat.shutdownNow();
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private void readLoop() {
        String reason = "The connection to the server was lost.";
        try {
            String line;
            while ((line = in.readLine()) != null) {
                Message m;
                try {
                    m = Message.decode(line);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String ref = m.get("ref");
                boolean reply = Frames.OK.equals(m.type()) || Frames.FAIL.equals(m.type()) || Frames.ROW.equals(m.type());
                if (reply && ref != null) {
                    Pending p = pending.get(ref);
                    if (p == null) {
                        continue;
                    }
                    if (Frames.ROW.equals(m.type())) {
                        p.rows.add(m);
                    } else {
                        pending.remove(ref);
                        p.future.complete(new Reply(m, p.rows));
                    }
                } else {
                    if (Frames.CLOSING.equals(m.type())) {
                        reason = m.get("reason", reason);
                    }
                    deliver(m);
                }
            }
        } catch (IOException e) {
            // fall through to close
        } finally {
            heartbeat.shutdownNow();
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
            IOException lost = new IOException(reason);
            pending.values().forEach(p -> p.future.completeExceptionally(lost));
            pending.clear();
            if (!closedByUs) {
                notifyClosed(reason);
            }
        }
    }

    private void deliver(Message m) {
        Listener l;
        synchronized (backlog) {
            l = listener;
            if (l == null) {
                backlog.add(m);
                return;
            }
        }
        SwingUtilities.invokeLater(() -> l.event(m));
    }

    private void notifyClosed(String reason) {
        Listener l;
        synchronized (backlog) {
            l = listener;
            if (l == null) {
                closeReason = reason;
                return;
            }
        }
        SwingUtilities.invokeLater(() -> l.closed(reason));
    }
}
