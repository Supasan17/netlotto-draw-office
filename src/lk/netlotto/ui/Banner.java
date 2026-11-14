package lk.netlotto.ui;

import javax.swing.JTextArea;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** Inline message strip (success, warning or error). Hidden when empty. */
public final class Banner extends JTextArea {

    private Pill.Tone tone = Pill.Tone.NEUTRAL;

    public Banner() {
        setEditable(false);
        setFocusable(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setOpaque(false);
        setFont(Ui.font(13.5f));
        setBorder(Ui.pad(10, 14, 10, 14));
        setVisible(false);
    }

    public void show(Pill.Tone tone, String text) {
        this.tone = tone;
        setText(text);
        setForeground(Pill.fg(tone));
        setVisible(text != null && !text.isEmpty());
        revalidate();
        repaint();
    }

    public void clear() {
        show(Pill.Tone.NEUTRAL, "");
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Ui.antialias(g2);
        g2.setColor(Pill.bg(tone));
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
        g2.setColor(Pill.fg(tone));
        g2.fillRoundRect(0, 0, 4, getHeight(), 4, 4);
        g2.dispose();
        super.paintComponent(g);
    }
}
