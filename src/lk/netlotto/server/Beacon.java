package lk.netlotto.server;

import lk.netlotto.core.Rules;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Announces the server on the local network. Every two seconds it sends one UDP datagram to the
 * broadcast address of every active network interface (and to 127.0.0.1 for desks on this computer):
 *
 * <pre>NETLOTTO-DRAWOFFICE port=6060 name=LAB-PC-07 online=3</pre>
 *
 * Desks listen on UDP {@link Rules#BEACON_PORT} and list every server they hear, so nobody has to
 * type an IP address. This is a push design: desks never send anything over UDP.
 */
final class Beacon implements Runnable {

    private final int tcpPort;
    private final IntSupplier online;
    private final String name;
    private volatile boolean running = true;
    private Thread thread;

    Beacon(int tcpPort, IntSupplier online) {
        this.tcpPort = tcpPort;
        this.online = online;
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            host = "server";
        }
        this.name = host.replace(' ', '-');
    }

    void start() {
        thread = new Thread(this, "beacon");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            while (running) {
                byte[] data = (Rules.BEACON_TAG + " port=" + tcpPort + " name=" + name + " online=" + online.getAsInt())
                        .getBytes(StandardCharsets.UTF_8);
                for (InetAddress target : targets()) {
                    try {
                        socket.send(new DatagramPacket(data, data.length, target, Rules.BEACON_PORT));
                    } catch (IOException ignored) {
                        // One unreachable interface must not stop the others.
                    }
                }
                Thread.sleep(Rules.BEACON_INTERVAL_MS);
            }
        } catch (IOException e) {
            Log.warn("Beacon stopped: " + e.getMessage() + ". Desks can still connect by IP address.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Set<InetAddress> targets() {
        Set<InetAddress> out = new LinkedHashSet<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
            out.add(InetAddress.getLoopbackAddress());
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress a : nic.getInterfaceAddresses()) {
                    if (a.getBroadcast() != null) {
                        out.add(a.getBroadcast());
                    }
                }
            }
        } catch (IOException ignored) {
            // Use what we have.
        }
        return out;
    }
}
