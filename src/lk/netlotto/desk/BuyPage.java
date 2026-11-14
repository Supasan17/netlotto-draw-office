package lk.netlotto.desk;

import lk.netlotto.ui.*;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Message;
import lk.netlotto.core.Rules;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Choose numbers and buy a ticket, or buy several quick pick tickets at once. */
final class BuyPage extends JPanel {

    private final Workspace ws;
    private final NumberCell[] cells = new NumberCell[Rules.HIGHEST + 1];
    private final TreeSet<Integer> chosen = new TreeSet<>();
    private final NumberRow selection = new NumberRow(false);
    private final JLabel counter = Ui.label("", Ui.font(13), Ui.MUTED);
    private final Btn clear = new Btn("Clear", Btn.Kind.SECONDARY);
    private final Btn random = new Btn("Random numbers", Btn.Kind.SECONDARY);
    private final Btn buy = new Btn("Buy ticket · " + Rules.TICKET_PRICE + " points", Btn.Kind.PRIMARY);
    private final Banner message = new Banner();

    private final SpinnerNumberModel quickCount = new SpinnerNumberModel(1, 1, Rules.MAX_QUICK_PICKS, 1);
    private final Btn quickBuy = new Btn("Buy quick pick tickets", Btn.Kind.PRIMARY);
    private final JLabel quickCost = Ui.label("", Ui.font(13), Ui.MUTED);

    private final JLabel stDraw = value();
    private final JLabel stSales = value();
    private final JLabel stClose = value();
    private final JLabel stPrice = value();
    private final JLabel stBalance = value();

    private final JPanel receipts = new JPanel();
    private final JLabel noReceipts = Ui.label("Tickets you buy in this session appear here.", Ui.font(13), Ui.MUTED);
    private final SecureRandom rnd = new SecureRandom();
    private boolean busy;

    BuyPage(Workspace ws) {
        super(new GridBagLayout());
        this.ws = ws;
        setOpaque(false);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridheight = 2;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 0.6;
        c.weighty = 1;
        c.insets = new Insets(0, 0, 0, 16);
        add(pickCard(), c);

        c.gridx = 1;
        c.gridheight = 1;
        c.weightx = 0.4;
        c.weighty = 0;
        c.insets = new Insets(0, 0, 16, 0);
        c.fill = GridBagConstraints.HORIZONTAL;
        add(quickCard(), c);

        c.gridy = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 0, 0);
        add(receiptCard(), c);

        quickCount.addChangeListener(e -> updateQuickCost());
        updateSelection();
        updateQuickCost();
        clockChanged();
        balanceChanged();
    }

    private static JLabel value() {
        JLabel l = Ui.label("–", Ui.bold(13.5f), Ui.TEXT);
        l.setHorizontalAlignment(JLabel.RIGHT);
        return l;
    }

    // ================================================================ layout

    private JComponent pickCard() {
        Card card = new Card("Choose " + Rules.PICK + " numbers",
                "Each combination can be sold only once per draw.", new BorderLayout(0, 18));
        JPanel grid = new JPanel(new GridLayout(5, 8, 8, 8));
        grid.setOpaque(false);
        for (int n = 1; n <= Rules.HIGHEST; n++) {
            NumberCell cell = new NumberCell(n);
            cells[n] = cell;
            cell.addActionListener(e -> toggle(cell));
            grid.add(cell);
        }
        JPanel gridWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        gridWrap.setOpaque(false);
        gridWrap.add(grid);
        card.body().add(gridWrap, BorderLayout.NORTH);

        JPanel lower = new JPanel();
        lower.setOpaque(false);
        lower.setLayout(new BoxLayout(lower, BoxLayout.Y_AXIS));
        JPanel divider = new JPanel();
        divider.setBackground(Ui.DIVIDER);
        divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        divider.setPreferredSize(new Dimension(10, 1));
        divider.setAlignmentX(LEFT_ALIGNMENT);
        lower.add(divider);
        lower.add(Box.createVerticalStrut(16));

        JLabel cap = Ui.caption("Your selection");
        cap.setAlignmentX(LEFT_ALIGNMENT);
        lower.add(cap);
        lower.add(Box.createVerticalStrut(8));
        JPanel selRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        selRow.setOpaque(false);
        selRow.add(selection);
        selRow.add(Box.createHorizontalStrut(16));
        selRow.add(counter);
        selRow.setAlignmentX(LEFT_ALIGNMENT);
        lower.add(selRow);
        lower.add(Box.createVerticalStrut(18));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        actions.add(clear);
        actions.add(Box.createHorizontalStrut(8));
        actions.add(random);
        actions.add(Box.createHorizontalStrut(8));
        actions.add(buy);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        lower.add(actions);
        lower.add(Box.createVerticalStrut(14));
        message.setAlignmentX(LEFT_ALIGNMENT);
        lower.add(message);

        clear.addActionListener(e -> {
            chosen.clear();
            message.clear();
            updateSelection();
        });
        random.addActionListener(e -> randomFill());
        buy.addActionListener(e -> buySelected());

        JPanel lowerTop = new JPanel(new BorderLayout());
        lowerTop.setOpaque(false);
        lowerTop.add(lower, BorderLayout.NORTH);
        card.body().add(lowerTop, BorderLayout.CENTER);
        return card;
    }

    private JComponent quickCard() {
        Card card = new Card("Quick pick", "The server chooses unsold combinations for you.", new BorderLayout(0, 14));

        JPanel status = new JPanel(new GridLayout(0, 2, 0, 8));
        status.setOpaque(false);
        status.add(Ui.label("Draw", Ui.font(13.5f), Ui.MUTED));
        status.add(stDraw);
        status.add(Ui.label("Sales", Ui.font(13.5f), Ui.MUTED));
        status.add(stSales);
        status.add(Ui.label("Draw in", Ui.font(13.5f), Ui.MUTED));
        status.add(stClose);
        status.add(Ui.label("Ticket price", Ui.font(13.5f), Ui.MUTED));
        status.add(stPrice);
        status.add(Ui.label("Your balance", Ui.font(13.5f), Ui.MUTED));
        status.add(stBalance);
        stPrice.setText(Ui.points(Rules.TICKET_PRICE));
        card.body().add(status, BorderLayout.NORTH);

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.DIVIDER), Ui.pad(14, 0, 0, 0)));
        JSpinner spinner = new JSpinner(quickCount);
        spinner.setFont(Ui.font(14));
        spinner.setPreferredSize(new Dimension(70, 38));
        JPanel left = new JPanel(new BorderLayout(10, 0));
        left.setOpaque(false);
        left.add(Ui.label("Tickets", Ui.font(13.5f), Ui.MUTED), BorderLayout.WEST);
        left.add(spinner, BorderLayout.CENTER);
        row.add(left, BorderLayout.WEST);
        row.add(quickBuy, BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(row, BorderLayout.NORTH);
        south.add(quickCost, BorderLayout.SOUTH);
        card.body().add(south, BorderLayout.SOUTH);
        quickBuy.addActionListener(e -> buyQuick());
        return card;
    }

    private JComponent receiptCard() {
        Card card = new Card("Purchased this session", null, new BorderLayout());
        receipts.setLayout(new BoxLayout(receipts, BoxLayout.Y_AXIS));
        receipts.setBackground(Ui.SURFACE);
        receipts.add(noReceipts);
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Ui.SURFACE);
        top.add(receipts, BorderLayout.NORTH);
        card.body().add(Ui.scroll(top), BorderLayout.CENTER);
        return card;
    }

    // ================================================================ selection

    private void toggle(NumberCell cell) {
        int n = cell.number();
        if (cell.isSelected()) {
            if (chosen.size() >= Rules.PICK) {
                cell.setSelected(false);
                message.show(Pill.Tone.WARN, "A ticket has exactly " + Rules.PICK + " numbers. Remove one first.");
                return;
            }
            chosen.add(n);
        } else {
            chosen.remove(n);
        }
        message.clear();
        updateSelection();
    }

    private void randomFill() {
        chosen.clear();
        while (chosen.size() < Rules.PICK) {
            chosen.add(1 + rnd.nextInt(Rules.HIGHEST));
        }
        message.clear();
        updateSelection();
    }

    private void updateSelection() {
        for (int n = 1; n <= Rules.HIGHEST; n++) {
            cells[n].setSelected(chosen.contains(n));
        }
        int[] nums = chosen.stream().mapToInt(Integer::intValue).toArray();
        selection.setNumbers(nums);
        int left = Rules.PICK - nums.length;
        counter.setText(left == 0 ? "Ready to buy" : left + (left == 1 ? " more number" : " more numbers") + " to choose");
        counter.setForeground(left == 0 ? Ui.SUCCESS : Ui.MUTED);
        updateButtons();
    }

    private void updateButtons() {
        boolean open = ws.clock().salesOpen();
        buy.setEnabled(!busy && open && chosen.size() == Rules.PICK && ws.balance() >= Rules.TICKET_PRICE);
        quickBuy.setEnabled(!busy && open && ws.balance() >= Rules.TICKET_PRICE);
        clear.setEnabled(!busy && !chosen.isEmpty());
        random.setEnabled(!busy);
        for (int n = 1; n <= Rules.HIGHEST; n++) {
            cells[n].setEnabled(!busy);
        }
    }

    private void updateQuickCost() {
        int n = quickCount.getNumber().intValue();
        quickCost.setText(n + (n == 1 ? " ticket costs " : " tickets cost ") + Ui.points((long) n * Rules.TICKET_PRICE) + ".");
    }

    // ================================================================ purchases

    private void buySelected() {
        if (chosen.size() != Rules.PICK) {
            return;
        }
        String csv = Rules.csv(chosen.stream().mapToInt(Integer::intValue).toArray());
        setBusy(true);
        message.show(Pill.Tone.NEUTRAL, "Sending your ticket to the server…");
        ws.call(r -> {
            setBusy(false);
            if (r.ok()) {
                Message h = r.head();
                ws.setBalance(h.getInt("balance", ws.balance()));
                addReceipt(h);
                message.show(Pill.Tone.GOOD, "Ticket " + Rules.ticketRef(h.getInt("ticket", 0)) + " is yours for draw "
                        + Rules.drawRef(h.getInt("draw", 0)) + ": " + Ui.numbers(h.get("numbers")) + ".");
                ws.feed("You bought " + Rules.ticketRef(h.getInt("ticket", 0)) + " (" + Ui.numbers(h.get("numbers")) + ").",
                        Pill.Tone.ACCENT);
                chosen.clear();
                updateSelection();
                ws.purchased();
            } else if (Frames.E_TAKEN.equals(r.code())) {
                message.show(Pill.Tone.BAD, r.message() + " You have not been charged.");
            } else {
                message.show(Pill.Tone.BAD, r.message());
            }
        }, Frames.BUY, "numbers", csv);
    }

    private void buyQuick() {
        int count = quickCount.getNumber().intValue();
        setBusy(true);
        message.show(Pill.Tone.NEUTRAL, "Buying " + count + " quick pick ticket" + (count == 1 ? "" : "s") + "…");
        ws.call(r -> {
            setBusy(false);
            if (r.ok()) {
                ws.setBalance(r.head().getInt("balance", ws.balance()));
                List<String> refs = new ArrayList<>();
                for (Message row : r.rows()) {
                    addReceipt(row);
                    refs.add(Rules.ticketRef(row.getInt("ticket", 0)));
                }
                message.show(Pill.Tone.GOOD, "Bought " + refs.size() + " quick pick ticket" + (refs.size() == 1 ? "" : "s")
                        + ": " + String.join(", ", refs) + ".");
                ws.feed("You bought " + refs.size() + " quick pick ticket" + (refs.size() == 1 ? "" : "s") + ".", Pill.Tone.ACCENT);
                ws.purchased();
            } else {
                message.show(Pill.Tone.BAD, r.message());
            }
        }, Frames.QUICK_PICK, "count", count);
    }

    private void addReceipt(Message m) {
        if (noReceipts.getParent() != null) {
            receipts.remove(noReceipts);
        }
        JPanel row = new JPanel(new BorderLayout(12, 4));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.DIVIDER), Ui.pad(10, 0, 10, 0)));
        JPanel left = new JPanel(new BorderLayout(0, 2));
        left.setOpaque(false);
        left.add(Ui.label(Rules.ticketRef(m.getInt("ticket", 0)), Ui.bold(13.5f), Ui.TEXT), BorderLayout.NORTH);
        left.add(Ui.label(Rules.drawRef(m.getInt("draw", 0)) + "  ·  " + Ui.time(m.getLong("at", 0)),
                Ui.font(12), Ui.SUBTLE), BorderLayout.SOUTH);
        row.add(left, BorderLayout.WEST);
        JLabel nums = Ui.label(Ui.numbers(m.get("numbers")), Ui.bold(13.5f), Ui.ACCENT);
        nums.setHorizontalAlignment(JLabel.RIGHT);
        row.add(nums, BorderLayout.EAST);
        row.setAlignmentX(LEFT_ALIGNMENT);
        receipts.add(row, 0);
        receipts.revalidate();
        receipts.repaint();
    }

    private void setBusy(boolean on) {
        busy = on;
        updateButtons();
    }

    // ================================================================ workspace callbacks

    void clockChanged() {
        Workspace.Clock k = ws.clock();
        if (k.draw > 0) {
            stDraw.setText(Rules.drawRef(k.draw));
            stClose.setText(Ui.clock(k.secs));
        }
        switch (k.phase) {
            case Frames.PHASE_OPEN:
                stSales.setText("Open");
                stSales.setForeground(Ui.SUCCESS);
                break;
            case Frames.PHASE_CLOSED:
                stSales.setText("Closed until the draw");
                stSales.setForeground(Ui.WARNING);
                break;
            default:
                stSales.setText("Paused");
                stSales.setForeground(Ui.MUTED);
        }
        updateButtons();
    }

    void balanceChanged() {
        stBalance.setText(Ui.points(ws.balance()));
        updateButtons();
    }
}
