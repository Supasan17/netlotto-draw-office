package lk.netlotto.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** Product mark: a rounded tile with "NL". */
public final class Mark extends JComponent {

    public Mark() {
        setPreferredSize(new Dimension(44, 44));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Ui.antialias(g2);
        g2.setColor(new Color(0x2E8B7A));
        g2.fillRoundRect(0, 0, 44, 44, 12, 12);
        g2.setColor(Color.WHITE);
        g2.setFont(Ui.bold(17));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString("NL", (44 - fm.stringWidth("NL")) / 2, (44 - fm.getHeight()) / 2 + fm.getAscent());
        g2.dispose();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }
}
