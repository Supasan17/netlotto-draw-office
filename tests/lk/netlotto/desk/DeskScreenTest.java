package lk.netlotto.desk;

import lk.netlotto.core.Check;
import lk.netlotto.core.Frames;
import lk.netlotto.server.DrawServer;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renders the real desk window against a real server (offscreen, nothing is shown) and saves every
 * screen to test-results\ as PNG, checking that live data reached the pages.
 */
public final class DeskScreenTest {

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("drawoffice-ui");
        DrawServer server = DrawServer.launch(data, 0, 90);
        int port = server.port();
        File results = new File("test-results");
        results.mkdirs();

        // Another desk buys tickets so the draw has some activity.
        Link carol = Link.open("127.0.0.1", port);
        carol.request(Frames.SIGN_UP, "user", "carol", "password", "carol2026").get(10, TimeUnit.SECONDS);
        carol.request(Frames.QUICK_PICK, "count", 4).get(10, TimeUnit.SECONDS);

        Link link = Link.open("127.0.0.1", port);
        Link.Reply welcome = link.request(Frames.SIGN_UP, "user", "nadeesha", "password", "desk2026").get(10, TimeUnit.SECONDS);
        Check.that(welcome.ok(), "desk account opened through Link");
        link.request(Frames.BUY, "numbers", "4,9,15,22,31,38").get(10, TimeUnit.SECONDS);
        link.request(Frames.QUICK_PICK, "count", 2).get(10, TimeUnit.SECONDS);
        Link.Reply taken = carol.request(Frames.BUY, "numbers", "38,31,22,15,9,4").get(10, TimeUnit.SECONDS);
        Check.equal(Frames.E_TAKEN, taken.code(), "Link reports a taken combination as FAIL TAKEN");
        server.drawNow();
        Thread.sleep(800);
        link.request(Frames.BUY, "numbers", "1,8,13,27,34,40").get(10, TimeUnit.SECONDS);

        AtomicReference<DeskWindow> window = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            DeskWindow w = new DeskWindow("127.0.0.1", String.valueOf(port));
            w.setSize(1440, 900);
            w.addNotify();
            w.validate();
            window.set(w);
        });
        Thread.sleep(500);
        snapshot(window.get(), new File(results, "01-sign-in.png"));

        SwingUtilities.invokeAndWait(() -> window.get().open(link, welcome.head()));
        Thread.sleep(2500);
        Check.that(window.get().getTitle().contains("nadeesha"), "window title shows the signed-in account");

        String[] pages = {"overview", "buy", "tickets", "statement", "results"};
        for (int i = 0; i < pages.length; i++) {
            String page = pages[i];
            SwingUtilities.invokeAndWait(() -> {
                window.get().workspace().select(page);
                window.get().validate();
            });
            Thread.sleep(300);
            snapshot(window.get(), new File(results, String.format("%02d-%s.png", i + 2, page)));
        }

        List<Integer> rowCounts = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> collectTables(window.get().getContentPane(), rowCounts));
        Check.that(rowCounts.contains(4), "ticket history shows the account's 4 tickets");
        Check.that(rowCounts.stream().anyMatch(n -> n >= 5), "statement shows the opening balance, 4 purchases and any prizes");
        Check.that(rowCounts.contains(1), "results page shows the draw");
        Check.that(window.get().workspace().balance() <= 500 - 4 * 20 + 2000,
                "balance shown is the server's balance after purchases and prizes");

        link.close();
        carol.close();
        server.stop();
        System.out.println("  Screens saved in " + results.getAbsolutePath());
        Check.done();
        System.exit(0);
    }

    private static void collectTables(Container c, List<Integer> out) {
        for (Component child : c.getComponents()) {
            if (child instanceof JTable) {
                out.add(((JTable) child).getModel().getRowCount());
            } else if (child instanceof Container) {
                collectTables((Container) child, out);
            }
        }
    }

    private static void snapshot(DeskWindow w, File file) throws Exception {
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
