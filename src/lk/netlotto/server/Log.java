package lk.netlotto.server;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Timestamped server log. Every line goes to the console and to any listeners (the server window),
 * and the most recent lines are kept so a window opened later can show what happened before.
 */
final class Log {

    record Line(String time, String level, String text) {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int KEEP = 1000;
    private static final Deque<Line> recent = new ArrayDeque<>();
    private static final List<Consumer<Line>> listeners = new CopyOnWriteArrayList<>();

    private Log() {
    }

    static void info(String text) {
        write("INFO", text);
    }

    static void warn(String text) {
        write("WARN", text);
    }

    static void error(String text) {
        write("ERROR", text);
    }

    private static void write(String level, String text) {
        Line line = new Line(LocalTime.now().format(TIME), level, text);
        synchronized (recent) {
            recent.addLast(line);
            if (recent.size() > KEEP) {
                recent.removeFirst();
            }
            System.out.println(line.time() + "  " + String.format("%-6s", level) + " " + text);
        }
        for (Consumer<Line> l : listeners) {
            l.accept(line);
        }
    }

    /** Lines logged so far (oldest first), then every new line as it happens. */
    static List<Line> subscribe(Consumer<Line> listener) {
        synchronized (recent) {
            listeners.add(listener);
            return new ArrayList<>(recent);
        }
    }
}
