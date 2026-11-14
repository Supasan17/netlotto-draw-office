package lk.netlotto.server;

import lk.netlotto.core.Check;
import lk.netlotto.core.Frames;

import javax.imageio.ImageIO;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renders the server window offscreen against a running server with two desks, saves every page to
 * test-results\ and checks the tables show the live state and the database.
 */
public final class ServerWindowTest {

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("drawoffice-sw");
        DrawServer server = DrawServer.launch(data, 0, 90);
        File results = new File("test-results");
        results.mkdirs();

        try (ServerIntegrationTest.TestDesk a = new ServerIntegrationTest.TestDesk(server.port());
             ServerIntegrationTest.TestDesk b = new ServerIntegrationTest.TestDesk(server.port())) {
            a.request(Frames.SIGN_UP, "user", "kavindi", "password", "kavindi2026");
            b.request(Frames.SIGN_UP, "user", "tharindu", "password", "tharindu2026");
            a.request(Frames.QUICK_PICK, "count", 3);
            b.request(Frames.BUY, "numbers", "2,9,17,24,31,40");
            server.drawNow();
            Thread.sleep(800);
            a.request(Frames.BUY, "numbers", "5,12,18,26,33,39");
            b.request(Frames.QUICK_PICK, "count", 2);

            AtomicReference<ServerWindow> window = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                ServerWindow w = new ServerWindow(server);
                w.setSize(1440, 900);
                w.addNotify();
                w.validate();
                window.set(w);
            });
            Thread.sleep(1500);

            String[] pages = {"activity", "desks", "draw", "accounts", "draws", "database"};
            List<List<Integer>> counts = new ArrayList<>();
            for (int i = 0; i < pages.length; i++) {
                String page = pages[i];
                SwingUtilities.invokeAndWait(() -> {
                    window.get().select(page);
                    window.get().validate();
                });
                Thread.sleep(400);
                File png = new File(results, String.format("%02d-server-%s.png", i + 7, page));
                snapshot(window.get(), png);
                List<Integer> rows = new ArrayList<>();
                SwingUtilities.invokeAndWait(() -> collect(window.get().getContentPane(), rows));
                counts.add(rows);
            }
            Check.that(counts.get(0).stream().anyMatch(n -> n >= 5), "activity shows the server's log lines");
            Check.that(counts.get(1).contains(2), "connected desks shows both desks");
            Check.that(counts.get(2).contains(3), "current draw shows the 3 tickets sold since the draw");
            Check.that(counts.get(3).contains(2), "accounts page lists both accounts");
            Check.that(counts.get(4).contains(1), "draw history shows the draw");
            Check.that(counts.get(5).contains(2), "database page shows the accounts table");
        } finally {
            server.stop();
        }
        Check.done();
        System.exit(0);
    }

    private static void collect(Container c, List<Integer> out) {
        for (Component child : c.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            if (child instanceof JTable) {
                out.add(((JTable) child).getModel().getRowCount());
            } else if (child instanceof Container) {
                collect((Container) child, out);
            }
        }
    }

    private static void snapshot(ServerWindow w, File file) throws Exception {
        AtomicReference<BufferedImage> img = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Container content = w.getContentPane();
            BufferedImage b = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = b.createGraphics();
            content.printAll(g);
            g.dispose();
            img.set(b);
        });
        ImageIO.write(img.get(), "png", file);
        Check.that(file.length() > 10_000, "rendered " + file.getName());
    }
}
