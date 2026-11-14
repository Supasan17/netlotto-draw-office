package lk.netlotto.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;

/** White rounded surface with an optional header (title, optional subtitle, optional actions). */
public class Card extends JPanel {

    private final JPanel body;

    public Card(LayoutManager bodyLayout) {
        this(null, null, bodyLayout);
    }

    public Card(String title, String subtitle, LayoutManager bodyLayout) {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(16, 18, 18, 18));
        body = new JPanel(bodyLayout);
        body.setOpaque(false);
        if (title != null) {
            JPanel head = new JPanel(new BorderLayout(12, 0));
            head.setOpaque(false);
            head.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            JLabel t = Ui.label(title, Ui.bold(15.5f), Ui.TEXT);
            text.add(t);
            if (subtitle != null) {
                text.add(Box.createVerticalStrut(2));
                text.add(Ui.label(subtitle, Ui.font(12.5f), Ui.MUTED));
            }
            head.add(text, BorderLayout.CENTER);
            add(head, BorderLayout.NORTH);
        }
        add(body, BorderLayout.CENTER);
    }

    public JPanel body() {
        return body;
    }

    /** Places a component at the right of the header (for example a button or filter). */
    public void setAction(JComponent action) {
        JPanel head = (JPanel) ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.NORTH);
        if (head != null) {
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.setOpaque(false);
            wrap.add(action, BorderLayout.NORTH);
            head.add(wrap, BorderLayout.EAST);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Ui.antialias(g2);
        g2.setColor(Ui.SURFACE);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g2.setColor(Ui.BORDER);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g2.dispose();
    }
}
