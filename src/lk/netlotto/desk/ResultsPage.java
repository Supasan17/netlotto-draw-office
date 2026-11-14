package lk.netlotto.desk;

import lk.netlotto.ui.*;

import lk.netlotto.core.Message;
import lk.netlotto.core.Rules;

import javax.swing.JPanel;
import javax.swing.JTable;
import java.awt.BorderLayout;
import java.util.List;

/** Winning numbers and totals of the most recent draws. */
final class ResultsPage extends JPanel {

    private final RowsModel model = new RowsModel(
            new String[]{"Draw", "Drawn at", "Winning numbers", "Tickets sold", "Winning tickets", "Jackpot paid"},
            m -> Rules.drawRef(m.getInt("draw", 0)),
            m -> Ui.dateTime(m.getLong("at", 0)),
            m -> Ui.numbers(m.get("numbers")),
            m -> String.valueOf(m.getInt("sold", 0)),
            m -> String.valueOf(m.getInt("winners", 0)),
            m -> m.getInt("jackpotWon", 0) > 0 ? Ui.points(m.getInt("jackpotWon", 0)) : "–");

    ResultsPage(Workspace ws) {
        super(new BorderLayout());
        setOpaque(false);
        Card card = new Card("Recent draws", "The last 50 draws, newest first. Prize by numbers matched: "
                + Rules.prizeTable() + ", 6 = share of the jackpot.", new BorderLayout());
        Btn refresh = new Btn("Refresh", Btn.Kind.SECONDARY);
        refresh.addActionListener(e -> ws.refreshResults());
        card.setAction(refresh);
        JTable t = new JTable(model);
        Ui.styleTable(t);
        t.getColumnModel().getColumn(1).setCellRenderer(new Cells.Muted());
        t.getColumnModel().getColumn(2).setCellRenderer(new Cells.Numbers());
        t.getColumnModel().getColumn(3).setCellRenderer(new Ui.Right());
        t.getColumnModel().getColumn(4).setCellRenderer(new Ui.Right());
        t.getColumnModel().getColumn(5).setCellRenderer(new Ui.Right());
        t.getColumnModel().getColumn(1).setPreferredWidth(170);
        t.getColumnModel().getColumn(2).setPreferredWidth(220);
        card.body().add(Ui.scroll(t), BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);
    }

    void setRows(List<Message> rows) {
        model.setRows(rows);
    }
}
