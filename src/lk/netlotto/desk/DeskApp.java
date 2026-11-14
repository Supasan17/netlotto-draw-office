package lk.netlotto.desk;

import lk.netlotto.ui.*;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * NetLotto Draw Office desk (client).
 *
 * <p>Usage: {@code java -jar drawoffice-desk.jar [serverHost [port]]}. Without arguments the desk
 * lists the servers it hears on the local network.
 */
public final class DeskApp {

    private DeskApp() {
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ignored) {
            // The default look and feel works too.
        }
        String host = args.length > 0 ? args[0] : null;
        String port = args.length > 1 ? args[1] : null;
        SwingUtilities.invokeLater(() -> new DeskWindow(host, port).setVisible(true));
    }
}
