package lk.netlotto.core;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One protocol frame: a type followed by named fields, sent as a single UTF-8 text line.
 *
 * <pre>BUY ref=7 numbers=3%2C7%2C12%2C20%2C33%2C40</pre>
 *
 * Field values are percent-encoded, so they may contain spaces, '=' or any other character
 * without breaking the framing. A frame never contains a newline.
 */
public final class Message {

    private final String type;
    private final Map<String, String> fields;

    private Message(String type, Map<String, String> fields) {
        this.type = type;
        this.fields = fields;
    }

    /** Builds a frame from alternating names and values: {@code of("OK", "ref", 3, "balance", 480)}. */
    public static Message of(String type, Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Fields must be name/value pairs");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            Object value = namesAndValues[i + 1];
            if (value != null) {
                map.put(String.valueOf(namesAndValues[i]), String.valueOf(value));
            }
        }
        return new Message(type, map);
    }

    /** Returns a copy with one more field. */
    public Message with(String name, Object value) {
        Map<String, String> map = new LinkedHashMap<>(fields);
        map.put(name, String.valueOf(value));
        return new Message(type, map);
    }

    /** Returns a copy with the same fields under a different frame type. */
    public Message withType(String newType) {
        return new Message(newType, new LinkedHashMap<>(fields));
    }

    public String type() {
        return type;
    }

    public Map<String, String> fields() {
        return Collections.unmodifiableMap(fields);
    }

    public boolean has(String name) {
        return fields.containsKey(name);
    }

    public String get(String name) {
        return fields.get(name);
    }

    public String get(String name, String fallback) {
        return fields.getOrDefault(name, fallback);
    }

    public int getInt(String name, int fallback) {
        String v = fields.get(name);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public long getLong(String name, long fallback) {
        String v = fields.get(name);
        if (v == null) {
            return fallback;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String encode() {
        StringBuilder sb = new StringBuilder(type);
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append(' ').append(e.getKey()).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** Parses one line. Throws IllegalArgumentException for an empty or malformed line. */
    public static Message decode(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("Empty frame");
        }
        String[] parts = line.trim().split(" +");
        String type = parts[0].toUpperCase();
        if (!type.matches("[A-Z_]{2,16}")) {
            throw new IllegalArgumentException("Bad frame type");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Bad field: " + parts[i]);
            }
            map.put(parts[i].substring(0, eq),
                    URLDecoder.decode(parts[i].substring(eq + 1), StandardCharsets.UTF_8));
        }
        return new Message(type, map);
    }

    @Override
    public String toString() {
        return encode();
    }
}
