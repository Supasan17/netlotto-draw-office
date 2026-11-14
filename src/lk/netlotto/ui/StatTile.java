package lk.netlotto.ui;

import javax.swing.BoxLayout;
import javax.swing.JLabel;
import java.awt.Color;

/** A key figure: caption, large value and a short note underneath. */
public final class StatTile extends Card {

    private final JLabel value = Ui.label("–", Ui.bold(26), Ui.TEXT);
    private final JLabel note = Ui.label(" ", Ui.font(12.5f), Ui.MUTED);

    public StatTile(String caption) {
        super(null);
        body().setLayout(new BoxLayout(body(), BoxLayout.Y_AXIS));
        JLabel c = Ui.caption(caption);
        body().add(c);
        body().add(javax.swing.Box.createVerticalStrut(6));
        body().add(value);
        body().add(javax.swing.Box.createVerticalStrut(2));
        body().add(note);
    }

    public void set(String v, String n) {
        value.setText(v);
        note.setText(n == null || n.isEmpty() ? " " : n);
    }

    public void setValueColor(Color c) {
        value.setForeground(c);
    }
}
