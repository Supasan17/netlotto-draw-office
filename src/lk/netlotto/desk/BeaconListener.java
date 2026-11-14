package lk.netlotto.desk;

import lk.netlotto.ui.*;

import lk.netlotto.core.Rules;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Listens for the server's UDP beacon and keeps a list of servers heard in the last few seconds.
 * The list is passed to the sign-in screen on the Swing thread whenever it changes.
 */
final class BeaconListener implements Runnable {

    record Found(String host, int port, String name, int online, long lastSeen) {
        String address() {
            return host + ":" + port;
        }

        @Override
        public String toString() {
            return name + "  –  " + address() + "  –  " + online + " online";
        }
    }

    private static final long FORGET_AFTER_MS = 7000;

    private final Consumer<List<Found>> onChange;
    private final Map<String, Found> servers = new LinkedHashMap<>();
    private volatile boolean running = true;
    private volatile DatagramSocket socket;
    private volatile String problem;

    BeaconListener(Consumer<List<Found>> onChange) {
        this.onChange = onChange;
    }

    void start() {
        Thread t = new Thread(this, "beacon-listener");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) {
            s.close();
        }
    }

    /** Non-null if the UDP port could not be opened (servers can still be typed in). */
    String problem() {
        return problem;
    }

    @Override
    public void run() {
        try (DatagramSocket s = new DatagramSocket(null)) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Rules.BEACON_PORT));
            s.setSoTimeout(1000);
            socket = s;
            byte[] buf = new byte[512];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    heard(p.getAddress().getHostAddress(), new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                } catch (SocketTimeoutException e) {
                    // fall through to expiry check
                }
                expire();
            }
        } catch (IOException e) {
            if (running) {
                problem = "Automatic server detection is unavailable on this computer (UDP " + Rules.BEACON_PORT
                        + " is in use). Enter the server address instead.";
                publish();
            }
        }
    }

    private void heard(String host, String text) {
        String[] parts = text.trim().split(" ");
        if (parts.length < 2 || !Rules.BEACON_TAG.equals(parts[0])) {
            return;
        }
        int port = Rules.TCP_PORT;
        int online = 0;
        String name = host;
        for (int i = 1; i < parts.length; i++) {
            String[] kv = parts[i].split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            try {
                switch (kv[0]) {
                    case "port":
                        port = Integer.parseInt(kv[1]);
                        break;
                    case "online":
                        online = Integer.parseInt(kv[1]);
                        break;
                    case "name":
                        name = kv[1];
                        break;
                    default:
                        break;
                }
            } catch (NumberFormatException ignored) {
                return;
            }
        }
        // The loopback copy and the LAN copy of one beacon describe the same server: prefer the LAN address.
        if (host.startsWith("127.")) {
            for (Found f : servers.values()) {
                if (f.name().equals(name) && f.port() == port && !f.host().startsWith("127.")) {
                    return;
                }
            }
        } else {
            servers.remove("127.0.0.1:" + port);
        }
        Found before = servers.put(host + ":" + port, new Found(host, port, name, online, System.currentTimeMillis()));
        if (before == null || before.online() != online) {
            publish();
        }
    }

    private void expire() {
        long now = System.currentTimeMillis();
        if (servers.values().removeIf(f -> now - f.lastSeen() > FORGET_AFTER_MS)) {
            publish();
        }
    }

    private void publish() {
        List<Found> list = new ArrayList<>(servers.values());
        list.sort(Comparator.comparing(Found::name));
        SwingUtilities.invokeLater(() -> onChange.accept(list));
    }
}
