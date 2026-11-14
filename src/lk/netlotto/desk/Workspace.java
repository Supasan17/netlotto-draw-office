package lk.netlotto.desk;

import lk.netlotto.ui.*;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Message;
import lk.netlotto.core.Rules;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The signed-in workspace: sidebar navigation, a status bar with the live draw clock, and five pages.
 * All server events arrive here (on the Swing thread) and are passed on to the pages.
 */
final class Workspace extends JPanel implements Link.Listener {

    /** Latest CLOCK values, read by the pages. */
    static final class Clock {
        int draw;
        String phase = Frames.PHASE_OPEN;
        int secs;
        int jackpot;
        int sold;
        int online;

        boolean salesOpen() {
            return Frames.PHASE_OPEN.equals(phase);
        }
    }

    private final DeskWindow window;
    private final Link link;
    private final String user;
    private final Clock clock = new Clock();
    private int balance;

    private final CardLayout pages = new CardLayout();
    private final JPanel pageHost = new JPanel(pages);
    private final List<NavItem> nav = new ArrayList<>();
    private final JLabel pageTitle = Ui.label("", Ui.bold(21), Ui.TEXT);
    private final JLabel pageNote = Ui.label("", Ui.font(13), Ui.MUTED);
    private final Pill phasePill = new Pill();
    private final JLabel countdown = Ui.label("", Ui.bold(14), Ui.TEXT);
    private final JLabel connection = Ui.label("", Ui.font(12.5f), Ui.MUTED);
    private final JLabel sideBalance = Ui.label("", Ui.bold(18), Color.WHITE);

    private final OverviewPage overview;
    private final BuyPage buy;
    private final TicketsPage tickets;
    private final StatementPage statement;
    private final ResultsPage results;
    private final Timer refreshSoon;
    private boolean ended;

    Workspace(DeskWindow window, Link link, Message welcome) {
        super(new BorderLayout());
        this.window = window;
        this.link = link;
        this.user = welcome.get("user", "");
        this.balance = welcome.getInt("balance", 0);
        setBackground(Ui.BG);

        overview = new OverviewPage(this);
        buy = new BuyPage(this);
        tickets = new TicketsPage(this);
        statement = new StatementPage(this);
        results = new ResultsPage(this);

        add(sidebar(), BorderLayout.WEST);
        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(Ui.BG);
        main.add(topBar(), BorderLayout.NORTH);
        pageHost.setBackground(Ui.BG);
        addPage("overview", "Overview", "Live position of the current draw and your account.", overview);
        addPage("buy", "Buy tickets", "Choose six numbers from 1 to " + Rules.HIGHEST + ", or let the server pick for you.", buy);
        addPage("tickets", "My tickets", "Every ticket you have bought and its result.", tickets);
        addPage("statement", "Statement", "Every addition to and deduction from your points, newest first.", statement);
        addPage("results", "Draw results", "Winning numbers of recent draws.", results);
        main.add(pageHost, BorderLayout.CENTER);
        add(main, BorderLayout.CENTER);

        refreshSoon = new Timer(500, e -> {
            refreshTickets();
            refreshStatement();
        });
        refreshSoon.setRepeats(false);
        connection.setText("Connected to " + link.address());
        setBalance(balance);
        select("overview");
    }

    void start() {
        link.attach(this);
        refreshTickets();
        refreshStatement();
        refreshResults();
        feed("Signed in as " + user + ".", Pill.Tone.NEUTRAL);
    }

    // ================================================================ layout

    private JComponent sidebar() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(Ui.SIDEBAR);
        side.setPreferredSize(new Dimension(236, 0));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(Ui.pad(22, 18, 0, 18));
        JPanel brand = new JPanel(new BorderLayout(12, 0));
        brand.setOpaque(false);
        brand.add(new Mark(), BorderLayout.WEST);
        JPanel words = new JPanel(new BorderLayout());
        words.setOpaque(false);
        words.add(Ui.label("NetLotto", Ui.bold(17), Color.WHITE), BorderLayout.NORTH);
        words.add(Ui.label("Draw Office", Ui.font(12.5f), Ui.SIDEBAR_MUTED), BorderLayout.SOUTH);
        brand.add(words, BorderLayout.CENTER);
        brand.setAlignmentX(LEFT_ALIGNMENT);
        brand.setMaximumSize(new Dimension(400, 44));
        top.add(brand);
        top.add(Box.createVerticalStrut(30));
        JLabel menu = Ui.label("MENU", Ui.bold(11.5f), Ui.SIDEBAR_MUTED);
        menu.setBorder(Ui.pad(0, 12, 8, 0));
        menu.setAlignmentX(LEFT_ALIGNMENT);
        top.add(menu);
        String[][] items = {{"overview", "Overview"}, {"buy", "Buy tickets"}, {"tickets", "My tickets"},
                {"statement", "Statement"}, {"results", "Draw results"}};
        for (String[] it : items) {
            NavItem n = new NavItem(it[0], it[1], this::select);
            n.setAlignmentX(LEFT_ALIGNMENT);
            nav.add(n);
            top.add(n);
            top.add(Box.createVerticalStrut(2));
        }
        side.add(top, BorderLayout.NORTH);

        JPanel account = new JPanel();
        account.setOpaque(false);
        account.setLayout(new BoxLayout(account, BoxLayout.Y_AXIS));
        account.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x23484A)), Ui.pad(18, 20, 20, 20)));
        JLabel who = Ui.label(user, Ui.bold(14.5f), Color.WHITE);
        JLabel cap = Ui.label("Available balance", Ui.font(12.5f), Ui.SIDEBAR_MUTED);
        for (JComponent c : new JComponent[]{who, cap, sideBalance}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
        }
        account.add(who);
        account.add(Box.createVerticalStrut(10));
        account.add(cap);
        account.add(Box.createVerticalStrut(2));
        account.add(sideBalance);
        account.add(Box.createVerticalStrut(16));
        Btn out = new Btn("Sign out", Btn.Kind.ON_DARK);
        out.setAlignmentX(LEFT_ALIGNMENT);
        out.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        out.addActionListener(e -> signOut());
        account.add(out);
        side.add(account, BorderLayout.SOUTH);
        return side;
    }

    private JComponent topBar() {
        JPanel bar = new JPanel(new BorderLayout(20, 0));
        bar.setBackground(Ui.SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.BORDER), Ui.pad(14, 28, 14, 28)));
        JPanel titles = new JPanel(new BorderLayout(0, 2));
        titles.setOpaque(false);
        titles.add(pageTitle, BorderLayout.NORTH);
        titles.add(pageNote, BorderLayout.SOUTH);
        bar.add(titles, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        right.setOpaque(false);
        JPanel stack = new JPanel(new BorderLayout(0, 2));
        stack.setOpaque(false);
        countdown.setHorizontalAlignment(JLabel.RIGHT);
        connection.setHorizontalAlignment(JLabel.RIGHT);
        stack.add(countdown, BorderLayout.NORTH);
        stack.add(connection, BorderLayout.SOUTH);
        right.add(stack);
        right.add(phasePill);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void addPage(String key, String title, String note, JComponent page) {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(Ui.BG);
        wrap.setBorder(Ui.pad(22, 28, 26, 28));
        wrap.add(page, BorderLayout.CENTER);
        wrap.putClientProperty("title", title);
        wrap.putClientProperty("note", note);
        wrap.setName(key);
        pageHost.add(wrap, key);
    }

    void select(String key) {
        pages.show(pageHost, key);
        for (Component c : pageHost.getComponents()) {
            if (key.equals(c.getName())) {
                JComponent j = (JComponent) c;
                pageTitle.setText((String) j.getClientProperty("title"));
                pageNote.setText((String) j.getClientProperty("note"));
            }
        }
        for (NavItem n : nav) {
            n.setActive(n.key().equals(key));
        }
    }

    // ================================================================ shared state for pages

    Clock clock() {
        return clock;
    }

    int balance() {
        return balance;
    }

    void setBalance(int value) {
        balance = value;
        sideBalance.setText(Ui.points(value));
        overview.balanceChanged();
        buy.balanceChanged();
    }

    void feed(String text, Pill.Tone tone) {
        overview.addActivity(System.currentTimeMillis() / 1000, text, tone);
    }

    /** Sends a request and runs the handler on the Swing thread with the reply (or a failure reply). */
    void call(Consumer<Link.Reply> handler, String type, Object... fields) {
        CompletableFuture<Link.Reply> f = link.request(type, fields);
        f.whenComplete((reply, error) -> SwingUtilities.invokeLater(() -> {
            if (ended) {
                return;
            }
            if (error != null) {
                handler.accept(new Link.Reply(Message.of(Frames.FAIL, "code", Frames.E_SERVER, "message",
                        "The connection to the server was lost."), List.of()));
            } else {
                handler.accept(reply);
            }
        }));
    }

    void refreshTickets() {
        call(r -> {
            if (r.ok()) {
                tickets.setRows(r.rows());
                overview.setRecentTickets(r.rows());
            }
        }, Frames.TICKETS);
    }

    void refreshStatement() {
        call(r -> {
            if (r.ok()) {
                statement.setRows(r.rows());
                setBalance(r.head().getInt("balance", balance));
            }
        }, Frames.STATEMENT);
    }

    void refreshResults() {
        call(r -> {
            if (r.ok()) {
                results.setRows(r.rows());
            }
        }, Frames.RESULTS);
    }

    /** Called by the buy page after a successful purchase. */
    void purchased() {
        refreshSoon.restart();
    }

    // ================================================================ events

    @Override
    public void event(Message m) {
        if (ended) {
            return;
        }
        switch (m.type()) {
            case Frames.CLOCK:
                clock.draw = m.getInt("draw", clock.draw);
                clock.phase = m.get("phase", clock.phase);
                clock.secs = m.getInt("secs", clock.secs);
                clock.jackpot = m.getInt("jackpot", clock.jackpot);
                clock.sold = m.getInt("sold", clock.sold);
                clock.online = m.getInt("online", clock.online);
                showClock();
                overview.clockChanged();
                buy.clockChanged();
                break;
            case Frames.RESULT:
                overview.setResult(m);
                if (m.getLong("at", 0) >= System.currentTimeMillis() / 1000 - 5) {
                    feed(Rules.drawRef(m.getInt("draw", 0)) + " drawn: " + Ui.numbers(m.get("numbers")), Pill.Tone.ACCENT);
                    refreshResults();
                    refreshSoon.restart();
                }
                break;
            case Frames.SETTLED:
                if (m.has("balance")) {
                    setBalance(m.getInt("balance", balance));
                }
                int prize = m.getInt("prize", 0);
                if (prize > 0) {
                    int matched = m.getInt("matches", 0);
                    feed(Rules.ticketRef(m.getInt("ticket", 0)) + " won " + Ui.points(prize) + " (" + matched
                            + (matched == 1 ? " match)" : " matches)"), Pill.Tone.GOOD);
                }
                refreshSoon.restart();
                break;
            case Frames.NOTICE:
                feed(m.get("text", ""), m.get("text", "").startsWith("Draw office:") ? Pill.Tone.WARN : Pill.Tone.NEUTRAL);
                break;
            default:
                break;
        }
    }

    private void showClock() {
        switch (clock.phase) {
            case Frames.PHASE_OPEN:
                phasePill.set("Sales open", Pill.Tone.GOOD);
                break;
            case Frames.PHASE_CLOSED:
                phasePill.set("Sales closed", Pill.Tone.WARN);
                break;
            default:
                phasePill.set("Sales paused", Pill.Tone.NEUTRAL);
        }
        countdown.setText(Rules.drawRef(clock.draw) + "   ·   draw in " + Ui.clock(clock.secs));
    }

    @Override
    public void closed(String reason) {
        if (ended) {
            return;
        }
        ended = true;
        refreshSoon.stop();
        window.backToSignIn(reason, true);
    }

    private void signOut() {
        ended = true;
        refreshSoon.stop();
        link.request(Frames.SIGN_OUT);
        // Give the server a moment to receive SIGNOUT before the socket closes.
        Timer t = new Timer(150, e -> link.close());
        t.setRepeats(false);
        t.start();
        window.backToSignIn("You have signed out.", false);
    }

    void disconnect() {
        ended = true;
        refreshSoon.stop();
        link.close();
    }
}
