package lk.netlotto.ui;

import lk.netlotto.core.Rules;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** A row of number tiles: winning numbers (filled) or a selection (outlined, with empty slots). */
public final class NumberRow extends JComponent {

    private static final int W = 40;
    private static final int H = 36;
    private static final int GAP = 8;

    private final boolean filled;
    private int[] numbers = new int[0];

    public NumberRow(boolean filled) {
        this.filled = filled;
        setFont(Ui.bold(15));
    }

    public void setNumbers(int[] numbers) {
        this.numbers = numbers == null ? new int[0] : numbers.clone();
        setToolTipText(numbers == null || numbers.length == 0 ? null : Ui.numbers(Rules.csv(this.numbers)));
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(Rules.PICK * W + (Rules.PICK - 1) * GAP, H);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Ui.antialias(g2);
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        int y = (getHeight() - H) / 2;
        for (int i = 0; i < Rules.PICK; i++) {
            int x = i * (W + GAP);
            if (i < numbers.length) {
                String s = String.format("%02d", numbers[i]);
                if (filled) {
                    g2.setColor(Ui.ACCENT);
                    g2.fillRoundRect(x, y, W, H, 10, 10);
                    g2.setColor(java.awt.Color.WHITE);
                } else {
                    g2.setColor(Ui.ACCENT_SOFT);
                    g2.fillRoundRect(x, y, W, H, 10, 10);
                    g2.setColor(Ui.ACCENT);
                    g2.drawRoundRect(x, y, W - 1, H - 1, 10, 10);
                }
                g2.drawString(s, x + (W - fm.stringWidth(s)) / 2, y + (H - fm.getHeight()) / 2 + fm.getAscent());
            } else {
                g2.setColor(Ui.BORDER);
                g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 4f}, 0f));
                g2.drawRoundRect(x, y, W - 1, H - 1, 10, 10);
                g2.setStroke(new BasicStroke(1f));
            }
        }
        g2.dispose();
    }
}
