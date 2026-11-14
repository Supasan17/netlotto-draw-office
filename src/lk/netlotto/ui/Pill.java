package lk.netlotto.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** Small rounded status label with a coloured dot, e.g. "Sales open". */
public final class Pill extends JComponent {

    public enum Tone { GOOD, WARN, BAD, NEUTRAL, ACCENT }

    private String text = "";
    private Tone tone = Tone.NEUTRAL;

    public Pill() {
        setFont(Ui.bold(12.5f));
    }

    public void set(String text, Tone tone) {
        this.text = text;
        this.tone = tone;
        revalidate();
        repaint();
    }

    public static Color fg(Tone t) {
        switch (t) {
            case GOOD:
                return Ui.SUCCESS;
            case WARN:
                return Ui.WARNING;
            case BAD:
                return Ui.DANGER;
            case ACCENT:
                return Ui.ACCENT;
            default:
                return Ui.MUTED;
        }
    }

    public static Color bg(Tone t) {
        switch (t) {
            case GOOD:
                return Ui.SUCCESS_SOFT;
            case WARN:
                return Ui.WARNING_SOFT;
            case BAD:
                return Ui.DANGER_SOFT;
            case ACCENT:
                return Ui.ACCENT_SOFT;
            default:
                return Ui.NEUTRAL_SOFT;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(fm.stringWidth(text) + 34, 26);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Ui.antialias(g2);
        int h = 26;
        int y = (getHeight() - h) / 2;
        int w = getPreferredSize().width;
        g2.setColor(bg(tone));
        g2.fillRoundRect(0, y, w, h, h, h);
        g2.setColor(fg(tone));
        g2.fillOval(11, y + h / 2 - 3, 7, 7);
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, 24, y + (h - fm.getHeight()) / 2 + fm.getAscent());
        g2.dispose();
    }
}
