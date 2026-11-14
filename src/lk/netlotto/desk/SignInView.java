package lk.netlotto.desk;

import lk.netlotto.ui.*;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Rules;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Sign in or open an account, choosing the server from the ones heard on the network. */
final class SignInView extends JPanel {

    private static final String SEARCHING = "Searching this network for servers…";

    private final DeskWindow window;
    private final JLabel heading = Ui.label("Sign in to your account", Ui.bold(24), Ui.TEXT);
    private final JLabel subheading = Ui.label("Use the account you opened at any desk on this network.",
            Ui.font(14), Ui.MUTED);
    private final Tab signInTab = new Tab("Sign in");
    private final Tab createTab = new Tab("Create account");
    private final JComboBox<Object> servers = new JComboBox<>();
    private final JTextField host = Ui.field(14);
    private final JTextField port = Ui.field(5);
    private final JTextField username = Ui.field(18);
    private final JPasswordField password = Ui.password();
    private final JPasswordField confirm = Ui.password();
    private final JLabel confirmCaption = Ui.caption("Confirm password");
    private final JLabel rules = Ui.label("<html><div style='width:310px'>" + Rules.USERNAME_RULE + "<br>"
            + Rules.PASSWORD_RULE + "</div></html>",
            Ui.font(12.5f), Ui.MUTED);
    private final Banner banner = new Banner();
    private final Btn submit = new Btn("Sign in", Btn.Kind.PRIMARY);
    private final JLabel detection = Ui.label("Servers on this network are listed automatically, or type an address.",
            Ui.font(12.5f), Ui.SUBTLE);

    private BeaconListener beacon;
    private Link link;
    private boolean creating;
    private boolean busy;

    SignInView(DeskWindow window, String presetHost, String presetPort) {
        super(new BorderLayout());
        this.window = window;
        setBackground(Ui.BG);

        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(Ui.BG);
        center.add(form());
        add(center, BorderLayout.CENTER);

        host.setText(presetHost != null ? presetHost : "127.0.0.1");
        port.setText(presetPort != null ? presetPort : String.valueOf(Rules.TCP_PORT));
        servers.setModel(new DefaultComboBoxModel<>(new Object[]{SEARCHING}));
        servers.setFont(Ui.font(13.5f));
        servers.setPreferredSize(new Dimension(10, 38));
        servers.addActionListener(e -> {
            Object o = servers.getSelectedItem();
            if (o instanceof BeaconListener.Found) {
                BeaconListener.Found f = (BeaconListener.Found) o;
                host.setText(f.host());
                port.setText(String.valueOf(f.port()));
            }
        });

        signInTab.addActionListener(e -> setCreating(false));
        createTab.addActionListener(e -> setCreating(true));
        ButtonGroup g = new ButtonGroup();
        g.add(signInTab);
        g.add(createTab);
        signInTab.setSelected(true);
        submit.addActionListener(e -> submit());
        for (JTextField f : new JTextField[]{host, port, username, password, confirm}) {
            f.addActionListener(e -> submit());
        }
        setCreating(false);
        startDetection();
    }

    private JComponent form() {
        JPanel p = new JPanel(new GridBagLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(420, super.getPreferredSize().height);
            }
        };
        p.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.anchor = GridBagConstraints.WEST;

        int y = 0;
        JPanel brand = new JPanel(new BorderLayout(12, 0));
        brand.setOpaque(false);
        brand.add(new Mark(), BorderLayout.WEST);
        JPanel names = new JPanel(new BorderLayout());
        names.setOpaque(false);
        names.add(Ui.label("NetLotto", Ui.bold(17), Ui.TEXT), BorderLayout.NORTH);
        names.add(Ui.label("Draw Office", Ui.font(12.5f), Ui.MUTED), BorderLayout.SOUTH);
        brand.add(names, BorderLayout.CENTER);
        c.gridy = y++;
        c.insets = new Insets(0, 0, 32, 0);
        p.add(brand, c);
        c.insets = new Insets(0, 0, 0, 0);
        c.gridy = y++;
        p.add(heading, c);
        c.gridy = y++;
        c.insets = new Insets(4, 0, 20, 0);
        p.add(subheading, c);

        JPanel tabs = new JPanel(new GridLayout(1, 2, 4, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Ui.antialias(g2);
                g2.setColor(new Color(0xE6EAEE));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
            }
        };
        tabs.setOpaque(false);
        tabs.setBorder(Ui.pad(4, 4, 4, 4));
        tabs.add(signInTab);
        tabs.add(createTab);
        c.gridy = y++;
        c.insets = new Insets(0, 0, 22, 0);
        p.add(tabs, c);

        c.gridy = y++;
        c.insets = new Insets(0, 0, 6, 0);
        p.add(Ui.caption("Server"), c);
        c.gridy = y++;
        c.insets = new Insets(0, 0, 8, 0);
        p.add(servers, c);

        JPanel address = new JPanel(new BorderLayout(8, 0));
        address.setOpaque(false);
        port.setPreferredSize(new Dimension(92, 38));
        address.add(host, BorderLayout.CENTER);
        address.add(port, BorderLayout.EAST);
        host.setToolTipText("Server IP address or computer name");
        port.setToolTipText("TCP port");
        c.gridy = y++;
        c.insets = new Insets(0, 0, 4, 0);
        p.add(address, c);
        c.gridy = y++;
        c.insets = new Insets(0, 0, 18, 0);
        p.add(detection, c);

        y = addField(p, c, y, Ui.caption("Username"), username);
        y = addField(p, c, y, Ui.caption("Password"), password);
        y = addField(p, c, y, confirmCaption, confirm);
        c.gridy = y++;
        c.insets = new Insets(0, 0, 14, 0);
        p.add(rules, c);

        c.gridy = y++;
        c.insets = new Insets(0, 0, 14, 0);
        p.add(banner, c);
        submit.setPreferredSize(new Dimension(10, 42));
        c.gridy = y++;
        c.insets = new Insets(0, 0, 0, 0);
        p.add(submit, c);
        return p;
    }

    private static int addField(JPanel p, GridBagConstraints c, int y, JLabel caption, JComponent field) {
        c.gridy = y++;
        c.insets = new Insets(0, 0, 6, 0);
        p.add(caption, c);
        c.gridy = y++;
        c.insets = new Insets(0, 0, 14, 0);
        p.add(field, c);
        return y;
    }

    private void setCreating(boolean on) {
        creating = on;
        heading.setText(on ? "Open an account" : "Sign in to your account");
        subheading.setText(on ? "New accounts start with " + Ui.points(Rules.OPENING_BALANCE) + "."
                : "Use the account you opened at any desk on this network.");
        submit.setText(on ? "Create account and sign in" : "Sign in");
        confirmCaption.setVisible(on);
        confirm.setVisible(on);
        rules.setVisible(on);
        banner.clear();
        revalidate();
        repaint();
    }

    // ================================================================ server detection

    private void startDetection() {
        if (beacon == null) {
            beacon = new BeaconListener(this::serversChanged);
            beacon.start();
        }
    }

    void pauseDetection() {
        if (beacon != null) {
            beacon.stop();
            beacon = null;
        }
    }

    private void serversChanged(List<BeaconListener.Found> found) {
        if (beacon == null) {
            return;
        }
        Object selected = servers.getSelectedItem();
        String selectedAddress = selected instanceof BeaconListener.Found ? ((BeaconListener.Found) selected).address() : null;
        if (found.isEmpty()) {
            servers.setModel(new DefaultComboBoxModel<>(new Object[]{SEARCHING}));
            String problem = beacon.problem();
            detection.setText(problem != null ? problem : "No server heard yet. You can type the address above.");
            return;
        }
        servers.setModel(new DefaultComboBoxModel<>(found.toArray()));
        detection.setText(found.size() == 1 ? "1 server found on this network." : found.size() + " servers found on this network.");
        BeaconListener.Found pick = found.get(0);
        for (BeaconListener.Found f : found) {
            if (f.address().equals(selectedAddress)) {
                pick = f;
            }
        }
        servers.setSelectedItem(pick);
    }

    // ================================================================ submit

    private void submit() {
        if (busy) {
            return;
        }
        String h = host.getText().trim();
        String user = username.getText().trim();
        char[] pw = password.getPassword();
        int p;
        try {
            p = Integer.parseInt(port.getText().trim());
            if (p < 1 || p > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            fail("Enter a port between 1 and 65535.", port);
            return;
        }
        if (h.isEmpty()) {
            fail("Enter the server address.", host);
            return;
        }
        if (user.isEmpty()) {
            fail("Enter your username.", username);
            return;
        }
        if (pw.length == 0) {
            fail("Enter your password.", password);
            return;
        }
        if (creating) {
            if (!Rules.validUsername(user)) {
                fail(Rules.USERNAME_RULE, username);
                return;
            }
            if (!Rules.validPassword(new String(pw))) {
                fail(Rules.PASSWORD_RULE, password);
                return;
            }
            if (!Arrays.equals(pw, confirm.getPassword())) {
                fail("The two passwords do not match.", confirm);
                return;
            }
        }
        String secret = new String(pw);
        Arrays.fill(pw, '\0');
        setBusy(true);
        banner.show(Pill.Tone.NEUTRAL, "Connecting to " + h + ":" + p + "…");
        String type = creating ? Frames.SIGN_UP : Frames.SIGN_IN;
        Thread worker = new Thread(() -> {
            Link.Reply reply = null;
            String error = null;
            try {
                Link l = link;
                if (l == null || !l.isOpen() || !l.address().equals(h + ":" + p)) {
                    if (l != null) {
                        l.close();
                    }
                    l = Link.open(h, p);
                    link = l;
                }
                reply = l.request(type, "user", user, "password", secret).get(15, TimeUnit.SECONDS);
            } catch (IOException e) {
                error = "Could not reach a server at " + h + ":" + p + ". Check the address and that the server is running.";
                link = null;
            } catch (ExecutionException e) {
                error = e.getCause() != null && e.getCause().getMessage() != null ? e.getCause().getMessage()
                        : "The connection was lost.";
                link = null;
            } catch (TimeoutException e) {
                error = "The server did not answer. Please try again.";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "Cancelled.";
            }
            Link.Reply r = reply;
            String err = error;
            SwingUtilities.invokeLater(() -> finished(r, err));
        }, "sign-in");
        worker.setDaemon(true);
        worker.start();
    }

    private void finished(Link.Reply reply, String error) {
        setBusy(false);
        if (error != null) {
            banner.show(Pill.Tone.BAD, error);
            return;
        }
        if (!reply.ok()) {
            banner.show(Frames.E_LOCKED.equals(reply.code()) ? Pill.Tone.WARN : Pill.Tone.BAD, reply.message());
            password.selectAll();
            password.requestFocusInWindow();
            return;
        }
        Link l = link;
        link = null;
        password.setText("");
        confirm.setText("");
        banner.clear();
        window.open(l, reply.head());
    }

    private void fail(String text, JComponent field) {
        banner.show(Pill.Tone.BAD, text);
        field.requestFocusInWindow();
    }

    private void setBusy(boolean on) {
        busy = on;
        submit.setEnabled(!on);
        signInTab.setEnabled(!on);
        createTab.setEnabled(!on);
        submit.setCursor(Cursor.getPredefinedCursor(on ? Cursor.WAIT_CURSOR : Cursor.HAND_CURSOR));
    }

    /** Called when returning from the workspace. */
    void reset(String reason, boolean problem) {
        setBusy(false);
        signInTab.setSelected(true);
        setCreating(false);
        if (reason != null) {
            banner.show(problem ? Pill.Tone.WARN : Pill.Tone.GOOD, reason);
        }
        startDetection();
    }

    void focusFirstField() {
        (username.getText().isEmpty() ? username : password).requestFocusInWindow();
    }

    void shutdown() {
        pauseDetection();
        if (link != null) {
            link.close();
        }
    }

    // ================================================================ pieces

    /** One half of the Sign in / Create account switch. */
    private static final class Tab extends JToggleButton {
        Tab(String text) {
            super(text);
            setFont(Ui.bold(13.5f));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(10, 34));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Ui.antialias(g2);
            if (isSelected()) {
                g2.setColor(Ui.SURFACE);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.setColor(Ui.BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            }
            g2.setFont(getFont());
            g2.setColor(isSelected() ? Ui.TEXT : Ui.MUTED);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }
}
