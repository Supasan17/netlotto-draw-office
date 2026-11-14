package lk.netlotto.desk;

import lk.netlotto.ui.*;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Message;

import javax.swing.JPanel;
import javax.swing.JTable;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.List;

/** The account statement: running balance after every purchase, prize and refund. */
final class StatementPage extends JPanel {

    private final StatTile balance = new StatTile("Current balance");
    private final StatTile spent = new StatTile("Spent on tickets");
    private final StatTile won = new StatTile("Prizes received");
    private final StatTile refunded = new StatTile("Refunds");

    private final RowsModel model = new RowsModel(
            new String[]{"Date and time", "Description", "Reference", "Amount (points)", "Balance (points)"},
            m -> Ui.dateTime(m.getLong("at", 0)),
            m -> m.get("text"),
            m -> m.get("reference"),
            m -> Ui.signedCredits(m.getInt("amount", 0)),
            m -> String.format("%,d", m.getInt("balance", 0)));

    StatementPage(Workspace ws) {
        super(new BorderLayout(0, 16));
        setOpaque(false);
        JPanel tiles = new JPanel(new GridLayout(1, 4, 16, 0));
        tiles.setOpaque(false);
        tiles.add(balance);
        tiles.add(spent);
        tiles.add(won);
        tiles.add(refunded);
        add(tiles, BorderLayout.NORTH);

        Card card = new Card("Account statement", "Recorded by the server in the same transaction as each change.",
                new BorderLayout());
        Btn refresh = new Btn("Refresh", Btn.Kind.SECONDARY);
        refresh.addActionListener(e -> ws.refreshStatement());
        card.setAction(refresh);
        JTable t = new JTable(model);
        Ui.styleTable(t);
        t.getColumnModel().getColumn(0).setCellRenderer(new Cells.Muted());
        t.getColumnModel().getColumn(3).setCellRenderer(new Cells.Amount());
        t.getColumnModel().getColumn(4).setCellRenderer(new Ui.Right());
        t.getColumnModel().getColumn(0).setPreferredWidth(170);
        t.getColumnModel().getColumn(1).setPreferredWidth(320);
        card.body().add(Ui.scroll(t), BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);
        setRows(List.of());
    }

    void setRows(List<Message> rows) {
        model.setRows(rows);
        long out = 0;
        long prizes = 0;
        long refunds = 0;
        int purchases = 0;
        int wins = 0;
        for (Message m : rows) {
            int amount = m.getInt("amount", 0);
            switch (m.get("type", "")) {
                case Frames.L_PURCHASE:
                    out += -amount;
                    purchases++;
                    break;
                case Frames.L_PRIZE:
                    prizes += amount;
                    wins++;
                    break;
                case Frames.L_REFUND:
                    refunds += amount;
                    break;
                default:
                    break;
            }
        }
        int now = rows.isEmpty() ? 0 : rows.get(0).getInt("balance", 0);
        balance.set(Ui.points(now), rows.isEmpty() ? "" : "After entry " + rows.get(0).get("reference"));
        spent.set(Ui.points(out), purchases + (purchases == 1 ? " ticket" : " tickets"));
        won.set(Ui.points(prizes), wins + (wins == 1 ? " winning ticket" : " winning tickets"));
        won.setValueColor(prizes > 0 ? Ui.SUCCESS : Ui.TEXT);
        refunded.set(Ui.points(refunds), "From draws that did not take place");
    }
}
