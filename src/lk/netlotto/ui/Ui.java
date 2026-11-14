package lk.netlotto.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/** Colours, type and shared component styling for the desk. */
public final class Ui {

    private Ui() {
    }

    // ---------------------------------------------------------------- palette
    public static final Color BG = new Color(0xF3F5F7);
    public static final Color SURFACE = Color.WHITE;
    public static final Color SURFACE_ALT = new Color(0xF8FAFB);
    public static final Color BORDER = new Color(0xDDE3E8);
    public static final Color DIVIDER = new Color(0xECEFF2);
    public static final Color TEXT = new Color(0x16202A);
    public static final Color MUTED = new Color(0x5B6B7B);
    public static final Color SUBTLE = new Color(0x8A97A5);

    public static final Color ACCENT = new Color(0x0F6E5F);
    public static final Color ACCENT_HOVER = new Color(0x0B5A4D);
    public static final Color ACCENT_SOFT = new Color(0xE2F1EE);

    public static final Color SIDEBAR = new Color(0x10292B);
    public static final Color SIDEBAR_ACTIVE = new Color(0x1C4143);
    public static final Color SIDEBAR_HOVER = new Color(0x173638);
    public static final Color SIDEBAR_TEXT = new Color(0xB4C5C4);
    public static final Color SIDEBAR_MUTED = new Color(0x7F9A99);

    public static final Color SUCCESS = new Color(0x157F3D);
    public static final Color SUCCESS_SOFT = new Color(0xE5F4EA);
    public static final Color DANGER = new Color(0xB42318);
    public static final Color DANGER_SOFT = new Color(0xFDECEA);
    public static final Color WARNING = new Color(0x9A5B06);
    public static final Color WARNING_SOFT = new Color(0xFDF2DD);
    public static final Color NEUTRAL_SOFT = new Color(0xEEF1F4);

    // ---------------------------------------------------------------- type
    private static final String FAMILY = pickFamily();

    private static String pickFamily() {
        List<String> installed = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String f : new String[]{"Segoe UI", "Inter", "Helvetica Neue", "Arial"}) {
            if (installed.contains(f)) {
                return f;
            }
        }
        return Font.SANS_SERIF;
    }

    public static Font font(float size) {
        return new Font(FAMILY, Font.PLAIN, Math.round(size));
    }

    public static Font bold(float size) {
        String semibold = FAMILY + " Semibold";
        Font f = new Font(semibold, Font.PLAIN, Math.round(size));
        return f.getFamily().equals(semibold) ? f : new Font(FAMILY, Font.BOLD, Math.round(size));
    }

    public static JLabel label(String text, Font font, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font);
        l.setForeground(color);
        return l;
    }

    /** Small uppercase-free caption above a field or value. */
    public static JLabel caption(String text) {
        return label(text, bold(12.5f), MUTED);
    }

    // ---------------------------------------------------------------- inputs
    public static JTextField field(int columns) {
        JTextField f = new JTextField(columns);
        styleField(f);
        return f;
    }

    public static JPasswordField password() {
        JPasswordField f = new JPasswordField(18);
        styleField(f);
        return f;
    }

    public static void styleField(JComponent f) {
        f.setFont(font(14));
        f.setForeground(TEXT);
        f.setBackground(SURFACE);
        f.setBorder(BorderFactory.createCompoundBorder(new RoundLine(), BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        f.setPreferredSize(new Dimension(f.getPreferredSize().width, 38));
        f.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                f.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                f.repaint();
            }
        });
    }

    /** Rounded field outline that turns accent-coloured while the field has focus. */
    public static final class RoundLine extends AbstractBorder {
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean focus = c.hasFocus();
            g2.setColor(!c.isEnabled() ? DIVIDER : focus ? ACCENT : BORDER);
            g2.setStroke(new BasicStroke(focus ? 1.6f : 1f));
            g2.drawRoundRect(x + 1, y + 1, w - 3, h - 3, 8, 8);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(2, 2, 2, 2);
        }
    }

    // ---------------------------------------------------------------- tables
    public static void styleTable(JTable t) {
        t.setFont(font(13.5f));
        t.setForeground(TEXT);
        t.setRowHeight(36);
        t.setShowVerticalLines(false);
        t.setShowHorizontalLines(true);
        t.setGridColor(DIVIDER);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setSelectionBackground(ACCENT_SOFT);
        t.setSelectionForeground(TEXT);
        t.setFillsViewportHeight(true);
        t.setBackground(SURFACE);
        t.setFocusable(false);
        t.setAutoCreateRowSorter(false);
        JTableHeader h = t.getTableHeader();
        h.setReorderingAllowed(false);
        h.setPreferredSize(new Dimension(h.getPreferredSize().width, 36));
        h.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean sel, boolean focus,
                                                           int row, int col) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, false, false, row, col);
                l.setFont(bold(12.5f));
                l.setForeground(MUTED);
                l.setBackground(SURFACE_ALT);
                l.setHorizontalAlignment(table.getColumnModel().getColumn(col).getCellRenderer() instanceof Right
                        ? SwingConstants.RIGHT : SwingConstants.LEFT);
                l.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER), BorderFactory.createEmptyBorder(0, 14, 0, 14)));
                return l;
            }
        });
        t.setDefaultRenderer(Object.class, new Cell());
    }

    /** Default body cell with comfortable padding. */
    public static class Cell extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean sel, boolean focus,
                                                       int row, int col) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, sel, false, row, col);
            l.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
            l.setFont(font(13.5f));
            l.setForeground(TEXT);
            l.setBackground(sel ? ACCENT_SOFT : SURFACE);
            return l;
        }
    }

    /** Right-aligned body cell for amounts. */
    public static class Right extends Cell {
        public Right() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }
    }

    public static JScrollPane scroll(JComponent c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(SURFACE);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    public static Border pad(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    // ---------------------------------------------------------------- formatting
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static String points(long amount) {
        return String.format("%,d %s", amount, Math.abs(amount) == 1 ? "point" : "points");
    }

    public static String signedCredits(long amount) {
        return (amount > 0 ? "+" : amount < 0 ? "−" : "") + String.format("%,d", Math.abs(amount));
    }

    public static String dateTime(long epochSeconds) {
        return DATE_TIME.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()));
    }

    public static String time(long epochSeconds) {
        return TIME.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()));
    }

    public static String clock(int seconds) {
        return String.format("%02d:%02d", Math.max(0, seconds) / 60, Math.max(0, seconds) % 60);
    }

    /** "3,7,12" -> "03  07  12". */
    public static String numbers(String csv) {
        if (csv == null || csv.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String n : csv.split(",")) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(String.format("%02d", Integer.parseInt(n.trim())));
        }
        return sb.toString();
    }

    public static int[] parseCsv(String csv) {
        if (csv == null || csv.isEmpty()) {
            return new int[0];
        }
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    public static void antialias(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }
}
