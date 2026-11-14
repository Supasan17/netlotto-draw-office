package lk.netlotto.desk;

import lk.netlotto.ui.*;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Message;
import lk.netlotto.core.Rules;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;

/** Every ticket the account has bought, filterable by status. */
final class TicketsPage extends JPanel {

    private static final String[] FILTERS = {"All tickets", "Awaiting draw", "Won", "No prize", "Refunded"};
    private static final String[] CODES = {null, Frames.T_OPEN, Frames.T_WON, Frames.T_NO_PRIZE, Frames.T_REFUNDED};

    private final RowsModel model = new RowsModel(
            new String[]{"Ticket", "Draw", "Purchased", "Numbers", "Matched", "Status", "Prize (points)"},
            m -> Rules.ticketRef(m.getInt("ticket", 0)),
            m -> Rules.drawRef(m.getInt("draw", 0)),
            m -> Ui.dateTime(m.getLong("at", 0)),
            m -> Ui.numbers(m.get("numbers")),
            m -> Frames.T_WON.equals(m.get("status")) || Frames.T_NO_PRIZE.equals(m.get("status"))
                    ? m.getInt("matches", 0) + " of " + Rules.PICK : "–",
            m -> m.get("status"),
            m -> m.getInt("prize", 0) > 0 ? String.format("%,d", m.getInt("prize", 0)) : "–");
    private final JComboBox<String> filter = new JComboBox<>(FILTERS);
    private final JLabel count = Ui.label("", Ui.font(13), Ui.MUTED);

    TicketsPage(Workspace ws) {
        super(new BorderLayout());
        setOpaque(false);
        Card card = new Card("Ticket history", "Stored on the server, so it follows your account to any desk.",
                new BorderLayout());
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        tools.setOpaque(false);
        filter.setFont(Ui.font(13.5f));
        tools.add(count);
        tools.add(filter);
        Btn refresh = new Btn("Refresh", Btn.Kind.SECONDARY);
        refresh.addActionListener(e -> ws.refreshTickets());
        tools.add(refresh);
        card.setAction(tools);

        JTable t = new JTable(model);
        Ui.styleTable(t);
        t.getColumnModel().getColumn(3).setCellRenderer(new Cells.Numbers());
        t.getColumnModel().getColumn(2).setCellRenderer(new Cells.Muted());
        t.getColumnModel().getColumn(5).setCellRenderer(new Cells.Status());
        t.getColumnModel().getColumn(6).setCellRenderer(new Ui.Right());
        t.getColumnModel().getColumn(2).setPreferredWidth(170);
        t.getColumnModel().getColumn(3).setPreferredWidth(200);
        t.getColumnModel().getColumn(5).setPreferredWidth(140);
        card.body().add(Ui.scroll(t), BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);

        filter.addActionListener(e -> {
            String code = CODES[filter.getSelectedIndex()];
            model.setFilter(m -> code == null || code.equals(m.get("status")));
            updateCount();
        });
        updateCount();
    }

    void setRows(List<Message> rows) {
        model.setRows(rows);
        updateCount();
    }

    private void updateCount() {
        int shown = model.getRowCount();
        int all = model.all().size();
        count.setText(shown == all ? all + (all == 1 ? " ticket" : " tickets") : shown + " of " + all + " tickets");
    }
}
