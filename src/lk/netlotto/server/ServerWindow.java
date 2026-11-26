package lk.netlotto.server;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Rules;
import lk.netlotto.ui.Btn;
import lk.netlotto.ui.Card;
import lk.netlotto.ui.Cells;
import lk.netlotto.ui.Mark;
import lk.netlotto.ui.NavItem;
import lk.netlotto.ui.Pill;
import lk.netlotto.ui.StatTile;
import lk.netlotto.ui.Ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The draw office's own window on the server computer: live figures, every event as it happens,
 * the connected desks, the tickets of the current draw, all accounts, draw history and a read-only
 * view of every database table, plus the operator controls (draw now, pause, resume, notice,
 * disconnect).
 */
final class ServerWindow extends JFrame {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss");
    private static final int ACTIVITY_LIMIT = 1000;

    private final DrawServer server;

    private final StatTile drawTile = new StatTile("Current draw");
    private final StatTile clockTile = new StatTile("Draw in");
    private final StatTile soldTile = new StatTile("Tickets sold");
    private final StatTile jackpotTile = new StatTile("Jackpot");
    private final StatTile onlineTile = new StatTile("Desks online");
    private final StatTile accountsTile = new StatTile("Accounts");
    private final Pill phasePill = new Pill();
    private final JLabel pageTitle = Ui.label("", Ui.bold(21), Ui.TEXT);
    private final JLabel pageNote = Ui.label("", Ui.font(13), Ui.MUTED);
    private final JTextField noticeField = Ui.field(28);
    private final Btn pause = new Btn("Pause sales", Btn.Kind.SECONDARY);
    private final Btn resume = new Btn("Resume sales", Btn.Kind.SECONDARY);

    private final CardLayout pages = new CardLayout();
    private final JPanel pageHost = new JPanel(pages);
    private final List<NavItem> nav = new ArrayList<>();
    private String currentPage = "activity";

    private final ListModel<Log.Line> activity = new ListModel<>(new String[]{"Time", "Level", "Event"},
            l -> new Object[]{l.time(), l.level(), l.text()});
    private final ListModel<Session> desks = new ListModel<>(
            new String[]{"Desk address", "Account", "Status", "Connected at"},
            s -> new Object[]{s.peer(), s.account() == null ? "–" : s.account().name(),
                    s.account() == null ? "Connected, not signed in" : "Signed in", time(s.connectedAt())});
    private final ListModel<DrawOffice.Entry> current = new ListModel<>(
            new String[]{"Ticket", "Account", "Numbers", "Bought at"},
            e -> new Object[]{Rules.ticketRef(e.ticketId()), e.accountName(), Ui.numbers(Rules.csv(e.numbers())), time(e.at())});
    private final ViewModel accounts = new ViewModel();
    private final ViewModel draws = new ViewModel();
    private final ViewModel database = new ViewModel();
    private final JComboBox<String> tableChoice = new JComboBox<>(Store.VIEWS.keySet().toArray(new String[0]));
    private final JLabel databaseCount = Ui.label("", Ui.font(13), Ui.MUTED);
    private final JTable deskTable = new JTable(desks);
    private int ticks;

    ServerWindow(DrawServer server) {
        super("NetLotto Draw Office – Server");
        this.server = server;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1200, 760));
        setSize(1440, 900);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.BG);
        root.add(sidebar(), BorderLayout.WEST);
        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(Ui.BG);
        main.add(topBar(), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(0, 16));
        body.setBackground(Ui.BG);
        body.setBorder(Ui.pad(20, 28, 24, 28));
        body.add(figures(), BorderLayout.NORTH);
        pageHost.setOpaque(false);
        addPage("activity", "Activity", "Every sign-in, purchase, draw and disconnect, newest first.", activityPage());
        addPage("desks", "Connected desks", "Desks connected to this server right now.", desksPage());
        addPage("draw", "Current draw", "Tickets sold so far for the draw that is open now.", tablePage(current, "Tickets in this draw",
                "Each combination appears at most once.", null, 2));
        addPage("accounts", "Accounts", "Every account in the database with its balance and activity.",
                tablePage(accounts, "All accounts", "Passwords are stored only as salted PBKDF2 hashes.", null, -1));
        addPage("draws", "Draw history", "Winning numbers and totals of past draws.",
                tablePage(draws, "Past draws", "Prize by numbers matched: " + Rules.prizeTable()
                        + ", 6 = share of the jackpot.", null, -1));
        addPage("database", "Database", "Read-only view of every table in data\\drawoffice.mv.db.", databasePage());
        body.add(pageHost, BorderLayout.CENTER);
        main.add(body, BorderLayout.CENTER);
        root.add(main, BorderLayout.CENTER);
        setContentPane(root);

        for (Log.Line l : Log.subscribe(line -> SwingUtilities.invokeLater(() -> addActivity(line)))) {
            addActivity(l);
        }
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int answer = JOptionPane.showConfirmDialog(ServerWindow.this,
                        "Stop the server? Every desk will be disconnected.", "Stop server",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer == JOptionPane.OK_OPTION) {
                    System.exit(0);
                }
            }
        });
        select("activity");
        refreshLive();
        refreshData();
        new Timer(1000, e -> {
            refreshLive();
            if (++ticks % 3 == 0) {
                refreshData();
            }
        }).start();
    }

    // ================================================================ layout

    private JComponent sidebar() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(Ui.SIDEBAR);
        side.setPreferredSize(new Dimension(250, 0));
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
        words.add(Ui.label("Server console", Ui.font(12.5f), Ui.SIDEBAR_MUTED), BorderLayout.SOUTH);
        brand.add(words, BorderLayout.CENTER);
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.setMaximumSize(new Dimension(400, 44));
        top.add(brand);
        top.add(Box.createVerticalStrut(30));
        JLabel menu = Ui.label("MONITOR", Ui.bold(11.5f), Ui.SIDEBAR_MUTED);
        menu.setBorder(Ui.pad(0, 12, 8, 0));
        menu.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(menu);
        String[][] items = {{"activity", "Activity"}, {"desks", "Connected desks"}, {"draw", "Current draw"},
                {"accounts", "Accounts"}, {"draws", "Draw history"}, {"database", "Database"}};
        for (String[] it : items) {
            NavItem n = new NavItem(it[0], it[1], this::select);
            nav.add(n);
            top.add(n);
            top.add(Box.createVerticalStrut(2));
        }
        side.add(top, BorderLayout.NORTH);

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x23484A)), Ui.pad(18, 20, 20, 20)));
        addLeft(info, Ui.label("Desks connect to", Ui.font(12.5f), Ui.SIDEBAR_MUTED));
        info.add(Box.createVerticalStrut(4));
        for (String ip : DrawServer.lanAddresses()) {
            addLeft(info, Ui.label(ip + " : " + server.port(), Ui.bold(14), Color.WHITE));
        }
        info.add(Box.createVerticalStrut(4));
        addLeft(info, Ui.label("UDP beacon on port " + Rules.BEACON_PORT, Ui.font(12), Ui.SIDEBAR_MUTED));
        addLeft(info, Ui.label("One draw every " + server.secondsPerDraw() + " seconds", Ui.font(12), Ui.SIDEBAR_MUTED));
        info.add(Box.createVerticalStrut(16));
        Btn stop = new Btn("Stop server", Btn.Kind.ON_DARK);
        stop.setAlignmentX(Component.LEFT_ALIGNMENT);
        stop.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        stop.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
        info.add(stop);
        side.add(info, BorderLayout.SOUTH);
        return side;
    }

    private static void addLeft(JPanel p, JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(c);
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
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 6));
        right.setOpaque(false);
        right.add(phasePill);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JComponent figures() {
        JPanel box = new JPanel(new BorderLayout(0, 16));
        box.setOpaque(false);
        JPanel tiles = new JPanel(new GridLayout(1, 6, 14, 0));
        tiles.setOpaque(false);
        for (StatTile t : new StatTile[]{drawTile, clockTile, soldTile, jackpotTile, onlineTile, accountsTile}) {
            tiles.add(t);
        }
        box.add(tiles, BorderLayout.NORTH);

        Card controls = new Card(new BorderLayout(16, 0));
        controls.setBorder(Ui.pad(12, 16, 12, 16));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        Btn draw = new Btn("Draw now", Btn.Kind.PRIMARY);
        draw.addActionListener(e -> server.office().drawNow());
        pause.addActionListener(e -> server.office().pause());
        resume.addActionListener(e -> server.office().resume());
        left.add(draw);
        left.add(pause);
        left.add(resume);
        controls.body().add(left, BorderLayout.WEST);

        JPanel notice = new JPanel(new BorderLayout(8, 0));
        notice.setOpaque(false);
        noticeField.setToolTipText("Message shown on every signed-in desk");
        Btn send = new Btn("Send notice", Btn.Kind.SECONDARY);
        Runnable sendNotice = () -> {                             //////send notice button eka click karanawa, desk ekata notice send karanawa
            String text = noticeField.getText().trim();
            if (!text.isEmpty()) {
                server.sendNotice(text);
                noticeField.setText("");
            }
        };
        send.addActionListener(e -> sendNotice.run());
        noticeField.addActionListener(e -> sendNotice.run());
        notice.add(Ui.label("Notice to all desks", Ui.font(13), Ui.MUTED), BorderLayout.WEST);
        notice.add(noticeField, BorderLayout.CENTER);
        notice.add(send, BorderLayout.EAST);
        controls.body().add(notice, BorderLayout.EAST);
        box.add(controls, BorderLayout.CENTER);
        return box;
    }

    private void addPage(String key, String title, String note, JComponent page) {
        page.setName(key);
        page.putClientProperty("title", title);
        page.putClientProperty("note", note);
        pageHost.add(page, key);
    }

    private JComponent activityPage() {
        Card card = new Card("Server activity", "The same lines are printed in the console window.", new BorderLayout());
        JTable t = new JTable(activity);
        Ui.styleTable(t);
        t.getColumnModel().getColumn(0).setCellRenderer(new Cells.Muted());
        t.getColumnModel().getColumn(1).setCellRenderer(new LevelCell());
        for (int c = 0; c < 2; c++) {
            t.getColumnModel().getColumn(c).setMinWidth(100);
            t.getColumnModel().getColumn(c).setMaxWidth(110);
        }
        card.body().add(Ui.scroll(t), BorderLayout.CENTER);
        return card;
    }

    private JComponent desksPage() {
        Btn kick = new Btn("Disconnect selected desk", Btn.Kind.SECONDARY);
        kick.addActionListener(e -> {
            int row = deskTable.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(this, "Select a desk in the table first.");
                return;
            }
            Session s = desks.get(row);
            String who = s.account() != null ? s.account().name() : s.peer();
            if (JOptionPane.showConfirmDialog(this, "Disconnect " + who + "?", "Disconnect desk",
                    JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                server.disconnect(s);
            }
        });
        Ui.styleTable(deskTable);
        deskTable.setFocusable(true);
        deskTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        deskTable.getColumnModel().getColumn(3).setCellRenderer(new Cells.Muted());
        return tableCard(deskTable, "Desks", "Each desk uses one reader and one writer thread on the server.", kick);
    }

    private JComponent databasePage() {
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        tools.setOpaque(false);
        tableChoice.setFont(Ui.font(13.5f));
        Btn refresh = new Btn("Refresh", Btn.Kind.SECONDARY);
        refresh.addActionListener(e -> loadDatabase());
        tableChoice.addActionListener(e -> loadDatabase());
        tools.add(databaseCount);
        tools.add(tableChoice);
        tools.add(refresh);
        JTable t = new JTable(database);
        Ui.styleTable(t);
        t.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        return tableCard(t, "Database tables", "H2 embedded database, refreshed every few seconds.", tools);
    }

    private JComponent tablePage(AbstractTableModel model, String title, String subtitle, JComponent action, int numbersColumn) {
        JTable t = new JTable(model);
        Ui.styleTable(t);
        if (numbersColumn >= 0) {
            t.getColumnModel().getColumn(numbersColumn).setCellRenderer(new Cells.Numbers());
        }
        return tableCard(t, title, subtitle, action);
    }

    private static JComponent tableCard(JTable t, String title, String subtitle, JComponent action) {
        Card card = new Card(title, subtitle, new BorderLayout());
        if (action != null) {
            card.setAction(action);
        }
        card.body().add(Ui.scroll(t), BorderLayout.CENTER);
        return card;
    }

    void select(String key) {
        currentPage = key;
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
        refreshData();
    }

    // ================================================================ refresh

    private void addActivity(Log.Line line) {
        activity.addFirst(line, ACTIVITY_LIMIT);
    }

    /** In-memory figures: cheap, every second. */
    private void refreshLive() {
        DrawOffice o = server.office();
        drawTile.set(Rules.drawRef(o.drawNo()), "Ticket price " + Rules.TICKET_PRICE + " points");
        clockTile.set(Ui.clock(o.secondsLeft()), Frames.PHASE_PAUSED.equals(o.phase()) ? "Countdown paused" : "until the draw");
        soldTile.set(String.valueOf(o.ticketsSold()), "in this draw");
        jackpotTile.set(String.format("%,d", o.jackpot()), "points");
        onlineTile.set(String.valueOf(server.online()), server.sessions().size() + " connected in total");
        switch (o.phase()) {
            case Frames.PHASE_OPEN:
                phasePill.set("Sales open", Pill.Tone.GOOD);
                break;
            case Frames.PHASE_CLOSED:
                phasePill.set("Sales closed for the draw", Pill.Tone.WARN);
                break;
            default:
                phasePill.set("Sales paused", Pill.Tone.NEUTRAL);
        }
        pause.setEnabled(!Frames.PHASE_PAUSED.equals(o.phase()));
        resume.setEnabled(Frames.PHASE_PAUSED.equals(o.phase()));
        desks.setIfChanged(server.sessions());
        current.setIfChanged(o.currentTickets());
    }

    /** Database figures: every three seconds, only for the page on screen. */
    private void refreshData() {
        try {
            accountsTile.set(String.valueOf(server.store().accountCount()), "in the database");
            switch (currentPage) {
                case "accounts":
                    accounts.set(server.store().view("Accounts"));
                    break;
                case "draws":
                    draws.set(server.store().view("Draws"));
                    break;
                case "database":
                    loadDatabase();
                    break;
                default:
                    break;
            }
        } catch (Store.StoreException e) {
            accountsTile.set("–", "Database unavailable");
        }
    }

    private void loadDatabase() {
        String name = (String) tableChoice.getSelectedItem();
        if (name == null) {
            return;
        }
        Store.Table t = server.store().view(name);
        database.set(t);
        databaseCount.setText(t.rows().size() + (t.rows().size() == 1 ? " row" : " rows"));
    }

    private static String time(long epochSeconds) {
        return TIME.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()));
    }

    // ================================================================ table models

    /** A table over a list of objects, one function turning each object into a row. */
    private static final class ListModel<T> extends AbstractTableModel {
        private final String[] names;
        private final java.util.function.Function<T, Object[]> row;
        private List<T> items = new ArrayList<>();
        private List<List<Object>> lastRows = List.of();

        ListModel(String[] names, java.util.function.Function<T, Object[]> row) {
            this.names = names;
            this.row = row;
        }

        T get(int i) {
            return items.get(i);
        }

        void addFirst(T item, int limit) {
            items.add(0, item);
            fireTableRowsInserted(0, 0);
            if (items.size() > limit) {
                items.remove(items.size() - 1);
                fireTableRowsDeleted(items.size(), items.size());
            }
        }

        /** Replaces the rows only when what they show has changed, so a selection survives refreshes. */
        void setIfChanged(List<T> next) {
            List<List<Object>> rows = new ArrayList<>();
            for (T t : next) {
                rows.add(java.util.Arrays.asList(row.apply(t)));
            }
            if (!Objects.equals(rows, lastRows)) {
                items = new ArrayList<>(next);
                lastRows = rows;
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int c) {
            return names[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            return row.apply(items.get(r))[c];
        }
    }

    /** A table showing a {@link Store.Table} with readable column titles. */
    private static final class ViewModel extends AbstractTableModel {
        private Store.Table table = new Store.Table(List.of(), List.of());

        void set(Store.Table t) {
            boolean sameColumns = t.columns().equals(table.columns());
            boolean same = sameColumns && t.rows().equals(table.rows());
            table = t;
            if (!sameColumns) {
                fireTableStructureChanged();
            } else if (!same) {
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return table.rows().size();
        }

        @Override
        public int getColumnCount() {
            return table.columns().size();
        }

        @Override
        public String getColumnName(int c) {
            String n = table.columns().get(c).replace('_', ' ');
            return Character.toUpperCase(n.charAt(0)) + n.substring(1);
        }

        @Override
        public Object getValueAt(int r, int c) {
            return table.rows().get(r).get(c);
        }
    }

    /** Colours WARN and ERROR in the activity table. */
    private static final class LevelCell extends Ui.Cell {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int r, int c) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, sel, focus, r, c);
            String v = String.valueOf(value);
            l.setForeground("ERROR".equals(v) ? Ui.DANGER : "WARN".equals(v) ? Ui.WARNING : Ui.SUBTLE);
            l.setFont(Ui.bold(12.5f));
            return l;
        }
    }
}
