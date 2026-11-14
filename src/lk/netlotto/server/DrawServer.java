package lk.netlotto.server;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Message;
import lk.netlotto.core.Rules;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NetLotto Draw Office server.
 *
 * <p>Threads: the main thread reads operator commands; an acceptor thread takes TCP connections;
 * every desk gets a reader and a writer thread from two fixed pools (at most {@link Rules#MAX_SESSIONS}
 * desks); the draw clock has its own thread; and the beacon thread announces the server on the LAN.
 *
 * <p>Usage: {@code java -jar drawoffice-server.jar [tcpPort] [secondsPerDraw]}, or
 * {@code java -jar drawoffice-server.jar --report} to print the database.
 */
public final class DrawServer implements DrawOffice.Hub {

    private static final int MAX_FAILURES = 5;
    private static final int LOCK_SECONDS = 60;

    private record Failures(int count, long lockedUntil) {}

    private int port;
    private final int secondsPerDraw;
    private final Store store;
    private final DrawOffice office;
    private final ExecutorService readers = Executors.newFixedThreadPool(Rules.MAX_SESSIONS, named("desk-reader"));
    private final ExecutorService writers = Executors.newFixedThreadPool(Rules.MAX_SESSIONS, named("desk-writer"));
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    /** Account id -> its session. One account can be signed in on one desk at a time. */
    private final Map<Integer, Session> signedIn = new ConcurrentHashMap<>();
    /** Client IP -> failed sign-in attempts. */
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private volatile ServerSocket listener;
    private volatile Beacon beacon;

    private DrawServer(Path dataFolder, int port, int secondsPerDraw) throws SQLException {
        this.port = port;
        this.secondsPerDraw = secondsPerDraw;
        this.store = new Store(dataFolder);
        int refunded = store.refundOpenTickets(Rules.TICKET_PRICE);
        if (refunded > 0) {
            Log.info("Refunded " + refunded + " ticket(s) from a draw interrupted by the last shutdown.");
        }
        this.office = new DrawOffice(store, this, secondsPerDraw);
    }

    public static void main(String[] args) {
        java.util.List<String> rest = new java.util.ArrayList<>(java.util.Arrays.asList(args));
        boolean consoleOnly = rest.remove("--console") || java.awt.GraphicsEnvironment.isHeadless();
        Path data = defaultDataFolder();
        if (rest.contains("--report")) {
            try {
                System.out.println("Database: " + data.resolve("drawoffice.mv.db"));
                DbReport.print(data);
            } catch (SQLException e) {
                System.out.println("Cannot read the database in " + data + ": " + e.getMessage());
            }
            return;
        }
        int port;
        int seconds;
        try {
            port = rest.size() > 0 ? Integer.parseInt(rest.get(0)) : Rules.TCP_PORT;
            seconds = rest.size() > 1 ? Integer.parseInt(rest.get(1)) : Rules.DEFAULT_DRAW_SECONDS;
        } catch (NumberFormatException e) {
            startupFailed(consoleOnly, "Usage: java -jar drawoffice-server.jar [--console] [tcpPort] [secondsPerDraw]");
            return;
        }
        if (seconds <= Rules.SALES_CLOSE_SECONDS + 5) {
            startupFailed(consoleOnly, "secondsPerDraw must be more than " + (Rules.SALES_CLOSE_SECONDS + 5) + ".");
            return;
        }
        DrawServer server;
        try {
            server = new DrawServer(data, port, seconds);
        } catch (SQLException e) {
            startupFailed(consoleOnly, "Cannot open the database in " + data + ".\n"
                    + "Is another server already running? (" + e.getMessage() + ")");
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "shutdown"));
        try {
            server.start();
        } catch (IOException e) {
            server.shutdown();
            startupFailed(consoleOnly, "Cannot listen on TCP port " + port + ": " + e.getMessage()
                    + "\nAnother server is probably running already. Close it, or start with another port.");
            return;
        }
        Log.info("Database: " + data.resolve("drawoffice.mv.db"));
        if (!consoleOnly) {
            javax.swing.SwingUtilities.invokeLater(() -> new ServerWindow(server).setVisible(true));
        }
        server.console();
    }

    private static void startupFailed(boolean consoleOnly, String message) {
        System.out.println(message);
        if (!consoleOnly) {
            javax.swing.JOptionPane.showMessageDialog(null, message, "NetLotto Draw Office server",
                    javax.swing.JOptionPane.ERROR_MESSAGE);
        }
        System.exit(1);
    }

    /**
     * The database folder: {@code data} next to the project, found from where this JAR is, so the
     * server uses the same database whether it is started from PowerShell or by double-clicking.
     * A JAR inside {@code dist} uses {@code <project>\data}; otherwise {@code data} next to the JAR.
     */
    static Path defaultDataFolder() {
        try {
            Path code = Path.of(DrawServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (java.nio.file.Files.isRegularFile(code)) {
                Path dir = code.toAbsolutePath().getParent();
                if (dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("dist")
                        && dir.getParent() != null) {
                    dir = dir.getParent();
                }
                return dir.resolve("data");
            }
        } catch (java.net.URISyntaxException | SecurityException | IllegalArgumentException e) {
            // fall back to the working folder
        }
        return Path.of("data").toAbsolutePath();
    }

    /**
     * Starts a server without the operator console, for automated tests.
     * Port 0 picks any free port; read it back with {@link #port()}.
     */
    public static DrawServer launch(Path dataFolder, int port, int secondsPerDraw) throws IOException, SQLException {
        DrawServer s = new DrawServer(dataFolder, port, secondsPerDraw);
        try {
            s.start();
        } catch (IOException e) {
            s.shutdown();
            throw e;
        }
        return s;
    }

    public int port() {
        return port;
    }

    /** Runs the draw immediately (the operator's {@code draw} command). */
    public void drawNow() {
        office.drawNow();
    }

    public void stop() {
        shutdown();
    }

    private void start() throws IOException {
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(port), 50);
        port = listener.getLocalPort();
        office.start();
        beacon = new Beacon(port, this::online);
        beacon.start();
        Thread acceptor = new Thread(this::acceptLoop, "acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        Log.info("NetLotto Draw Office listening on TCP " + port + ", beacon on UDP " + Rules.BEACON_PORT + ".");
        Log.info("Accounts: " + store.accountCount() + " | draw every " + secondsPerDraw + " s, sales close "
                + Rules.SALES_CLOSE_SECONDS + " s before | " + office.summary());
        Log.info("Type 'help' for operator commands.");
    }

    private void acceptLoop() {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                if (sessions.size() >= Rules.MAX_SESSIONS) {
                    refuseFull(socket);
                    continue;
                }
                Session s = new Session(socket, this);
                sessions.add(s);
                Log.info("Desk connected from " + s.peer() + " (" + sessions.size() + " connected).");
                readers.execute(s);
            } catch (SocketException e) {
                return; // listener closed during shutdown
            } catch (IOException e) {
                Log.warn("Accept failed: " + e.getMessage());
            }
        }
    }

    private static void refuseFull(Socket socket) {
        try (socket) {
            socket.getOutputStream().write((Message.of(Frames.CLOSING, "reason",
                    "The server is full. Please try again later.").encode() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Nothing to do.
        }
    }

    // ================================================================ used by the server window

    List<Session> sessions() {
        List<Session> list = new ArrayList<>(sessions);
        list.sort(java.util.Comparator.comparingLong(Session::connectedAt));
        return list;
    }

    int secondsPerDraw() {
        return secondsPerDraw;
    }

    void disconnect(Session s) {
        Log.info("Draw office disconnected " + (s.account() != null ? s.account().name() : s.peer()) + ".");
        s.close("disconnected by the draw office", true);
    }

    void sendNotice(String text) {
        broadcastNotice("Draw office: " + text);
        Log.info("Notice sent: " + text);
    }

    /** IPv4 addresses desks on the LAN can use to reach this server. */
    static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics = java.net.NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                String name = (nic.getDisplayName() + " " + nic.getName()).toLowerCase(java.util.Locale.ROOT);
                // Skip adapters other computers cannot reach (VirtualBox, VMware, Hyper-V, WSL).
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()
                        || name.matches(".*(virtualbox|vmware|hyper-v|vethernet|wsl|host-only).*")) {
                    continue;
                }
                for (java.net.InterfaceAddress a : nic.getInterfaceAddresses()) {
                    if (a.getAddress() instanceof java.net.Inet4Address) {
                        out.add(a.getAddress().getHostAddress());
                    }
                }
            }
        } catch (IOException ignored) {
            // none
        }
        if (out.isEmpty()) {
            out.add("127.0.0.1");
        }
        return out;
    }

    // ================================================================ used by sessions

    Store store() {
        return store;
    }

    DrawOffice office() {
        return office;
    }

    void startWriter(Runnable loop) {
        writers.execute(loop);
    }

    /** Registers the account as online. False if it is already signed in elsewhere. */
    boolean claim(Store.Account account, Session session) {
        return signedIn.putIfAbsent(account.id(), session) == null;
    }

    void ended(Session session, String reason) {
        sessions.remove(session);
        Store.Account acc = session.account();
        String who = acc != null ? acc.name() + " (" + session.peer() + ")" : session.peer();
        Log.info("Desk " + who + " disconnected: " + reason + ".");
        if (acc != null && signedIn.remove(acc.id(), session)) {
            broadcastNotice(acc.name() + " went offline.");
        }
    }

    int lockedSeconds(String host) {
        Failures f = failures.get(host);
        if (f == null) {
            return 0;
        }
        long left = f.lockedUntil() - System.currentTimeMillis();
        return left > 0 ? (int) Math.ceil(left / 1000.0) : 0;
    }

    /** Records a failed attempt and returns how many attempts remain before the lock. */
    int recordFailure(String host) {
        Failures f = failures.compute(host, (h, old) -> {
            int count = (old == null || old.lockedUntil() > 0 ? 0 : old.count()) + 1;
            long until = count >= MAX_FAILURES ? System.currentTimeMillis() + LOCK_SECONDS * 1000L : 0;
            return new Failures(count, until);
        });
        if (f.lockedUntil() > 0) {
            Log.warn("Sign-in locked for " + LOCK_SECONDS + " s for " + host + " after " + MAX_FAILURES + " failures.");
            return 0;
        }
        return MAX_FAILURES - f.count();
    }

    void clearFailures(String host) {
        failures.remove(host);
    }

    void broadcastNotice(String text) {
        broadcast(Message.of(Frames.NOTICE, "text", text, "at", System.currentTimeMillis() / 1000));
    }

    // ================================================================ DrawOffice.Hub

    @Override
    public void broadcast(Message event) {
        for (Session s : signedIn.values()) {
            s.post(event);
        }
    }

    @Override
    public void sendTo(int accountId, Message event) {
        Session s = signedIn.get(accountId);
        if (s != null) {
            s.post(event);
        }
    }

    @Override
    public int online() {
        return signedIn.size();
    }

    // ================================================================ operator console

    private void console() {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.trim();
                if (cmd.isEmpty()) {
                    continue;
                }
                String word = cmd.split("\\s+", 2)[0].toLowerCase();
                String rest = cmd.length() > word.length() ? cmd.substring(word.length()).trim() : "";
                switch (word) {
                    case "help":
                        System.out.println("  status            current draw, phase, tickets and jackpot");
                        System.out.println("  sessions          connected desks and who is signed in");
                        System.out.println("  draw              run the draw now");
                        System.out.println("  pause | resume    stop or restart ticket sales");
                        System.out.println("  notice <text>     send a notice to every signed-in desk");
                        System.out.println("  kick <username>   disconnect a signed-in account");
                        System.out.println("  stop              shut the server down");
                        break;
                    case "status":
                        Log.info(office.summary() + " | online " + online() + " | accounts " + store.accountCount());
                        break;
                    case "sessions":
                        if (sessions.isEmpty()) {
                            Log.info("No desks connected.");
                        }
                        for (Session s : sessions) {
                            Store.Account a = s.account();
                            Log.info(s.peer() + "  " + (a == null ? "(not signed in)" : a.name()));
                        }
                        break;
                    case "draw":
                        office.drawNow();
                        break;
                    case "pause":
                        office.pause();
                        break;
                    case "resume":
                        office.resume();
                        break;
                    case "notice":
                        if (rest.isEmpty()) {
                            System.out.println("Usage: notice <text>");
                        } else {
                            broadcastNotice("Draw office: " + rest);
                            Log.info("Notice sent.");
                        }
                        break;
                    case "kick":
                        kick(rest);
                        break;
                    case "stop":
                    case "quit":
                        System.exit(0);
                        return;
                    default:
                        System.out.println("Unknown command. Type 'help'.");
                }
            }
        } catch (IOException e) {
            Log.error("Console closed: " + e.getMessage());
        }
        // stdin closed (server started without a console): keep serving until the process is killed.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
            // exit
        }
    }

    private void kick(String name) {
        for (Session s : signedIn.values()) {
            if (s.account() != null && s.account().name().equalsIgnoreCase(name)) {
                s.close("disconnected by the draw office", true);
                return;
            }
        }
        Log.info("No signed-in account called '" + name + "'.");
    }

    private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();

    private void shutdown() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        Log.info("Shutting down.");
        for (Session s : sessions) {
            s.close("the server is shutting down", true);
        }
        if (beacon != null) {
            beacon.stop();
        }
        office.stop();
        try {
            if (listener != null) {
                listener.close();
            }
        } catch (IOException ignored) {
            // Closing anyway.
        }
        writers.shutdown();
        try {
            writers.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            // Continue.
        }
        readers.shutdownNow();
        store.close();
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
