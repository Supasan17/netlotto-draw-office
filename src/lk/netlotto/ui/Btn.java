package lk.netlotto.ui;

import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Flat rounded button in one of four styles. */
public final class Btn extends JButton {

    public enum Kind { PRIMARY, SECONDARY, QUIET, ON_DARK }

    private final Kind kind;
    private boolean hover;

    public Btn(String text, Kind kind) {
        super(text);
        this.kind = kind;
        setFont(Ui.bold(13.5f));
        setFocusPainted(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }
        });
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(fm.stringWidth(getText()) + (kind == Kind.QUIET ? 16 : 36), 38);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Ui.antialias(g2);
        boolean on = isEnabled();
        boolean pressed = getModel().isPressed();
        Color fill;
        Color line = null;
        Color text;
        switch (kind) {
            case PRIMARY:
                fill = !on ? new Color(0xB9CFCA) : pressed || hover ? Ui.ACCENT_HOVER : Ui.ACCENT;
                text = Color.WHITE;
                break;
            case SECONDARY:
                fill = !on ? Ui.SURFACE_ALT : pressed ? Ui.NEUTRAL_SOFT : hover ? Ui.SURFACE_ALT : Ui.SURFACE;
                line = Ui.BORDER;
                text = on ? Ui.TEXT : Ui.SUBTLE;
                break;
            case ON_DARK:
                fill = hover ? Ui.SIDEBAR_ACTIVE : Ui.SIDEBAR_HOVER;
                text = on ? Color.WHITE : Ui.SIDEBAR_MUTED;
                break;
            default:
                fill = hover && on ? Ui.ACCENT_SOFT : new Color(0, 0, 0, 0);
                text = on ? Ui.ACCENT : Ui.SUBTLE;
        }
        int w = getWidth();
        int h = getHeight();
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
        if (line != null) {
            g2.setColor(line);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
        }
        if (isFocusOwner() && on) {
            g2.setColor(new Color(Ui.ACCENT.getRed(), Ui.ACCENT.getGreen(), Ui.ACCENT.getBlue(), 90));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(1, 1, w - 3, h - 3, 8, 8);
        }
        g2.setFont(getFont());
        g2.setColor(text);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(getText(), (w - fm.stringWidth(getText())) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
        g2.dispose();
    }
}
