package lk.netlotto.desk;

import lk.netlotto.ui.*;

import javax.swing.JToggleButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** One selectable number in the ticket grid. */
final class NumberCell extends JToggleButton {

    private final int number;
    private boolean hover;

    NumberCell(int number) {
        super(String.format("%02d", number));
        this.number = number;
        setFont(Ui.bold(14));
        setFocusPainted(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName("Number " + number);
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

    int number() {
        return number;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(48, 40);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(38, 32);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Ui.antialias(g2);
        int w = getWidth() - 1;
        int h = getHeight() - 1;
        Color fill;
        Color line;
        Color text;
        if (!isEnabled()) {
            fill = Ui.SURFACE_ALT;
            line = Ui.DIVIDER;
            text = Ui.SUBTLE;
        } else if (isSelected()) {
            fill = hover ? Ui.ACCENT_HOVER : Ui.ACCENT;
            line = fill;
            text = Color.WHITE;
        } else {
            fill = hover ? Ui.ACCENT_SOFT : Ui.SURFACE;
            line = hover ? Ui.ACCENT : Ui.BORDER;
            text = Ui.TEXT;
        }
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, w, h, 9, 9);
        g2.setColor(line);
        g2.drawRoundRect(0, 0, w, h, 9, 9);
        if (isFocusOwner()) {
            g2.setColor(new Color(Ui.ACCENT.getRed(), Ui.ACCENT.getGreen(), Ui.ACCENT.getBlue(), 110));
            g2.drawRoundRect(1, 1, w - 2, h - 2, 8, 8);
        }
        g2.setFont(getFont());
        g2.setColor(text);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
        g2.dispose();
    }
}
