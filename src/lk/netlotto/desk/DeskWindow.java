package lk.netlotto.desk;

import lk.netlotto.ui.*;

import lk.netlotto.core.Message;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** The desk window: the sign-in screen, then the workspace once signed in. */
final class DeskWindow extends JFrame {

    private static final String SIGN_IN = "signin";
    private static final String WORKSPACE = "workspace";

    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final SignInView signIn;
    private Workspace workspace;

    DeskWindow(String host, String port) {
        super("NetLotto Draw Office");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 740));
        setSize(1360, 860);
        setLocationRelativeTo(null);
        signIn = new SignInView(this, host, port);
        root.add(signIn, SIGN_IN);
        setContentPane(root);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                signIn.focusFirstField();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                signIn.shutdown();
                if (workspace != null) {
                    workspace.disconnect();
                }
                System.exit(0);
            }
        });
    }

    /** Called by the sign-in screen after the server accepted the account. */
    void open(Link link, Message welcome) {
        if (workspace != null) {
            root.remove(workspace);
        }
        workspace = new Workspace(this, link, welcome);
        root.add(workspace, WORKSPACE);
        cards.show(root, WORKSPACE);
        setTitle("NetLotto Draw Office – " + welcome.get("user"));
        signIn.pauseDetection();
        workspace.start();
    }

    Workspace workspace() {
        return workspace;
    }

    /** Back to the sign-in screen, optionally explaining why. */
    void backToSignIn(String reason, boolean problem) {
        if (workspace != null) {
            root.remove(workspace);
            workspace = null;
        }
        setTitle("NetLotto Draw Office");
        signIn.reset(reason, problem);
        cards.show(root, SIGN_IN);
        signIn.focusFirstField();
    }
}
