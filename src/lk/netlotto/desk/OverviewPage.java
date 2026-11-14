package lk.netlotto.desk;

import lk.netlotto.ui.*;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Message;
import lk.netlotto.core.Rules;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.List;

/** Overview: the draw clock in figures, the latest result, recent tickets and the activity feed. */
final class OverviewPage extends JPanel {

    private static final int ACTIVITY_LIMIT = 80;

    private final Workspace ws;
    private final StatTile drawTile = new StatTile("Current draw");
    private final StatTile jackpotTile = new StatTile("Jackpot");
    private final StatTile soldTile = new StatTile("Tickets sold");
    private final StatTile balanceTile = new StatTile("Your balance");

    private final JLabel resultTitle = Ui.label("No draw has taken place yet", Ui.bold(14.5f), Ui.TEXT);
    private final NumberRow resultNumbers = new NumberRow(true);
    private final JLabel resultMeta = Ui.label("The first result appears here when the clock reaches zero.",
            Ui.font(13), Ui.MUTED);

    private final RowsModel recent = new RowsModel(
            new String[]{"Ticket", "Numbers", "Status", "Prize (points)"},
            m -> Rules.ticketRef(m.getInt("ticket", 0)),
            m -> Ui.numbers(m.get("numbers")),
            m -> m.get("status"),
            m -> Frames.T_WON.equals(m.get("status")) ? String.format("%,d", m.getInt("prize", 0)) : "–");
    private final JLabel noTickets = Ui.label("You have not bought any tickets yet.", Ui.font(13), Ui.MUTED);
    private final ActivityList activity = new ActivityList();

    OverviewPage(Workspace ws) {
        super(new GridBagLayout());
        this.ws = ws;
        setOpaque(false);

        JPanel tiles = new JPanel(new GridLayout(1, 4, 16, 0));
        tiles.setOpaque(false);
        tiles.add(drawTile);
        tiles.add(jackpotTile);
        tiles.add(soldTile);
        tiles.add(balanceTile);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 16, 0);
        add(tiles, c);

        c.gridwidth = 1;
        c.gridy = 1;
        c.weightx = 0.62;
        c.insets = new Insets(0, 0, 16, 16);
        add(resultCard(), c);

        c.gridy = 2;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 0, 16);
        add(recentCard(), c);

        c.gridx = 1;
        c.gridy = 1;
        c.gridheight = 2;
        c.weightx = 0.38;
        c.insets = new Insets(0, 0, 0, 0);
        add(activityCard(), c);

        clockChanged();
        balanceChanged();
    }

    private JComponent resultCard() {
        Card card = new Card("Latest result", null, new BorderLayout(0, 12));
        card.body().add(resultTitle, BorderLayout.NORTH);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(resultNumbers);
        card.body().add(row, BorderLayout.CENTER);
        card.body().add(resultMeta, BorderLayout.SOUTH);
        return card;
    }

    private JComponent recentCard() {
        Card card = new Card("Recent tickets", "Your six most recent purchases", new BorderLayout());
        Btn all = new Btn("View all", Btn.Kind.QUIET);
        all.addActionListener(e -> ws.select("tickets"));
        card.setAction(all);
        recent.setLimit(6);
        JTable t = new JTable(recent);
        Ui.styleTable(t);
        t.getColumnModel().getColumn(1).setCellRenderer(new Cells.Numbers());
        t.getColumnModel().getColumn(2).setCellRenderer(new Cells.Status());
        t.getColumnModel().getColumn(3).setCellRenderer(new Ui.Right());
        int[] min = {104, 160, 150, 120};
        int[] pref = {110, 200, 160, 120};
        for (int c = 0; c < min.length; c++) {
            t.getColumnModel().getColumn(c).setMinWidth(min[c]);
            t.getColumnModel().getColumn(c).setPreferredWidth(pref[c]);
        }
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(Ui.scroll(t), BorderLayout.CENTER);
        noTickets.setBorder(Ui.pad(10, 14, 0, 0));
        holder.add(noTickets, BorderLayout.SOUTH);
        card.body().add(holder, BorderLayout.CENTER);
        return card;
    }

    private JComponent activityCard() {
        Card card = new Card("Activity", "Results, prizes and notices as they happen", new BorderLayout());
        JScrollPane sp = Ui.scroll(activity);
        card.body().add(sp, BorderLayout.CENTER);
        return card;
    }

    // ================================================================ updates

    void clockChanged() {
        Workspace.Clock k = ws.clock();
        if (k.draw == 0) {
            return;
        }
        String phase;
        switch (k.phase) {
            case Frames.PHASE_OPEN:
                phase = "Sales open · draw in " + Ui.clock(k.secs);
                break;
            case Frames.PHASE_CLOSED:
                phase = "Sales closed · drawing in " + Ui.clock(k.secs);
                break;
            default:
                phase = "Sales paused by the draw office";
        }
        drawTile.set(Rules.drawRef(k.draw), phase);
        jackpotTile.set(Ui.points(k.jackpot), "Grows by " + Rules.JACKPOT_SHARE + " points with every ticket");
        soldTile.set(String.valueOf(k.sold), k.online + (k.online == 1 ? " desk" : " desks") + " online");
    }

    void balanceChanged() {
        int b = ws.balance();
        balanceTile.set(Ui.points(b), b >= Rules.TICKET_PRICE
                ? "Enough for " + b / Rules.TICKET_PRICE + " tickets"
                : "Not enough for a ticket (" + Rules.TICKET_PRICE + " points)");
    }

    void setResult(Message m) {
        resultTitle.setText("Draw " + Rules.drawRef(m.getInt("draw", 0)) + "  ·  " + Ui.dateTime(m.getLong("at", 0)));
        resultNumbers.setNumbers(Ui.parseCsv(m.get("numbers")));
        int jackpot = m.getInt("jackpotWon", 0);
        resultMeta.setText(m.getInt("sold", 0) + " tickets sold  ·  " + m.getInt("winners", 0) + " winning tickets  ·  "
                + (jackpot > 0 ? "Jackpot of " + Ui.points(jackpot) + " won" : "Jackpot not won, carried forward"));
    }

    void setRecentTickets(List<Message> rows) {
        recent.setRows(rows);
        noTickets.setVisible(rows.isEmpty());
    }

    void addActivity(long at, String text, Pill.Tone tone) {
        activity.add(at, text, tone);
    }

    // ================================================================ activity feed

    /** Vertical list of feed entries that wraps text to the available width. */
    private static final class ActivityList extends JPanel implements Scrollable {
        ActivityList() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(Ui.SURFACE);
        }

        void add(long at, String text, Pill.Tone tone) {
            JPanel row = new JPanel(new BorderLayout(12, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.DIVIDER), Ui.pad(10, 2, 10, 2)));
            JComponent dot = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    Ui.antialias(g2);
                    g2.setColor(Pill.fg(tone));
                    g2.fillOval(1, 6, 8, 8);
                    g2.dispose();
                }
            };
            dot.setPreferredSize(new Dimension(10, 18));
            JPanel dotWrap = new JPanel(new BorderLayout());
            dotWrap.setOpaque(false);
            dotWrap.add(dot, BorderLayout.NORTH);
            row.add(dotWrap, BorderLayout.WEST);

            JPanel words = new JPanel(new BorderLayout(0, 2));
            words.setOpaque(false);
            JTextArea body = new JTextArea(text);
            body.setEditable(false);
            body.setFocusable(false);
            body.setLineWrap(true);
            body.setWrapStyleWord(true);
            body.setOpaque(false);
            body.setFont(Ui.font(13.5f));
            body.setForeground(Ui.TEXT);
            body.setBorder(null);
            words.add(body, BorderLayout.CENTER);
            words.add(Ui.label(Ui.time(at), Ui.font(12), Ui.SUBTLE), BorderLayout.SOUTH);
            row.add(words, BorderLayout.CENTER);
            row.setAlignmentX(LEFT_ALIGNMENT);

            add(row, 0);
            while (getComponentCount() > ACTIVITY_LIMIT) {
                remove(getComponentCount() - 1);
            }
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(300, 300);
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return r.height - 32;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
