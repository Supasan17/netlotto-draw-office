package lk.netlotto.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/** One entry in the dark sidebar menu. */
public final class NavItem extends JComponent {

    private final String key;
    private final String text;
    private boolean active;
    private boolean hover;

    public NavItem(String key, String text, Consumer<String> onSelect) {
        this.key = key;
        this.text = text;
        setFont(Ui.bold(14));
        setPreferredSize(new Dimension(200, 40));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        setAlignmentX(LEFT_ALIGNMENT);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onSelect.accept(key);
            }

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

    public String key() {
        return key;
    }

    public void setActive(boolean on) {
        active = on;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Ui.antialias(g2);
        if (active || hover) {
            g2.setColor(active ? Ui.SIDEBAR_ACTIVE : Ui.SIDEBAR_HOVER);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 9, 9);
        }
        if (active) {
            g2.setColor(new Color(0x5CC2AE));
            g2.fillRoundRect(0, 9, 3, getHeight() - 18, 3, 3);
        }
        g2.setFont(getFont());
        g2.setColor(active ? Color.WHITE : Ui.SIDEBAR_TEXT);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, 14, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
        g2.dispose();
    }
}
