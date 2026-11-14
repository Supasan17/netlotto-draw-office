package lk.netlotto.ui;

import lk.netlotto.core.Frames;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;
import java.awt.FlowLayout;

/** Table cell renderers shared by the pages. */
public final class Cells {

    private Cells() {
    }

    public static String statusText(String status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case Frames.T_OPEN:
                return "Awaiting draw";
            case Frames.T_WON:
                return "Won";
            case Frames.T_NO_PRIZE:
                return "No prize";
            case Frames.T_REFUNDED:
                return "Refunded";
            default:
                return status;
        }
    }

    public static Pill.Tone statusTone(String status) {
        if (status == null) {
            return Pill.Tone.NEUTRAL;
        }
        switch (status) {
            case Frames.T_OPEN:
                return Pill.Tone.ACCENT;
            case Frames.T_WON:
                return Pill.Tone.GOOD;
            case Frames.T_REFUNDED:
                return Pill.Tone.WARN;
            default:
                return Pill.Tone.NEUTRAL;
        }
    }

    /** Renders a ticket status code as a coloured pill. */
    public static final class Status implements TableCellRenderer {
        private final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 5));
        private final Pill pill = new Pill();

        public Status() {
            panel.add(pill);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int r, int c) {
            String s = value == null ? "" : value.toString();
            pill.set(statusText(s), statusTone(s));
            panel.setBackground(sel ? Ui.ACCENT_SOFT : Ui.SURFACE);
            return panel;
        }
    }

    /** Signed amount: green for additions, default for deductions. */
    public static final class Amount extends Ui.Right {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int r, int c) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, sel, focus, r, c);
            String s = value == null ? "" : value.toString();
            l.setForeground(s.startsWith("+") ? Ui.SUCCESS : Ui.TEXT);
            l.setFont(Ui.bold(13.5f));
            return l;
        }
    }

    /** Ticket numbers in a fixed rhythm, slightly emphasised. */
    public static final class Numbers extends Ui.Cell {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int r, int c) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, sel, focus, r, c);
            l.setFont(Ui.bold(13.5f));
            l.setForeground(Ui.ACCENT);
            return l;
        }
    }

    /** Muted secondary text such as dates. */
    public static final class Muted extends Ui.Cell {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int r, int c) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, sel, focus, r, c);
            l.setForeground(Ui.MUTED);
            return l;
        }
    }
}
