package lk.netlotto.ui;

import lk.netlotto.core.Message;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Read-only table model over ROW frames, with one function per column and an optional filter. */
public final class RowsModel extends AbstractTableModel {

    private final String[] names;
    private final List<Function<Message, Object>> columns;
    private List<Message> all = new ArrayList<>();
    private List<Message> shown = new ArrayList<>();
    private Predicate<Message> filter = m -> true;
    private int limit = Integer.MAX_VALUE;

    @SafeVarargs
    @SuppressWarnings("varargs")
    public RowsModel(String[] names, Function<Message, Object>... columns) {
        this.names = names;
        this.columns = List.of(columns);
    }

    public void setRows(List<Message> rows) {
        all = new ArrayList<>(rows);
        apply();
    }

    public void setFilter(Predicate<Message> f) {
        filter = f;
        apply();
    }

    public void setLimit(int max) {
        limit = max;
        apply();
    }

    public List<Message> all() {
        return all;
    }

    public Message row(int i) {
        return shown.get(i);
    }

    private void apply() {
        shown = new ArrayList<>();
        for (Message m : all) {
            if (shown.size() >= limit) {
                break;
            }
            if (filter.test(m)) {
                shown.add(m);
            }
        }
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
        return shown.size();
    }

    @Override
    public int getColumnCount() {
        return names.length;
    }

    @Override
    public String getColumnName(int c) {
        return names[c];
    }

    @Override
    public Object getValueAt(int r, int c) {
        return columns.get(c).apply(shown.get(r));
    }
}
