package nixtruffle.util;

import nixtruffle.runtime.Bytes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small JSON model for the fetchers and flakes (registries, lock files, API responses), written
 * the way nlohmann::json writes it. Objects are {@link TreeMap}s (sorted keys, like nlohmann's
 * default {@code std::map}), arrays {@link List}s, numbers {@link Long} or {@link Double}, strings
 * byte strings (see {@link Bytes}).
 */
public final class Json {
    private Json() {}

    public static final Object NULL = new Object() {
        @Override
        public String toString() { return "null"; }
    };

    // ------------------------------------------------------------ writing

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, -1, 0);
        return sb.toString();
    }

    /** {@code dump(indent)}: nlohmann's pretty printing. */
    public static String write(Object v, int indent) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, indent, 0);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb, int indent, int level) {
        if (v == null || v == NULL) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(s, sb);
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
            sb.append(v);
        } else if (v instanceof Double d) {
            sb.append(d);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : (m instanceof TreeMap ? m : new TreeMap<>(m)).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, level + 1);
                quote((String) e.getKey(), sb);
                sb.append(indent >= 0 ? ": " : ":");
                write(e.getValue(), sb, indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (Object x : l) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, level + 1);
                write(x, sb, indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + v);
        }
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent < 0) return;
        sb.append('\n');
        sb.append(" ".repeat(indent * level));
    }

    /** A JSON string as nlohmann::json writes it (UTF-8 passed through, control characters escaped). */
    public static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------ parsing

    /** Parses JSON (a byte string); throws {@link IllegalArgumentException} on malformed input. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != text.length()) throw p.fail("trailing characters");
        return v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        IllegalArgumentException fail(String message) {
            return new IllegalArgumentException("JSON parse error at offset " + pos + ": " + message);
        }

        void ws() {
            while (pos < s.length() && " \t\n\r".indexOf(s.charAt(pos)) >= 0) pos++;
        }

        boolean eat(char c) {
            ws();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        void expect(char c) {
            if (!eat(c)) throw fail("expected '" + c + "'");
        }

        Object value() {
            ws();
            if (pos >= s.length()) throw fail("unexpected end of input");
            char c = s.charAt(pos);
            if (c == '{') {
                pos++;
                TreeMap<String, Object> m = new TreeMap<>();
                if (!eat('}')) {
                    do {
                        ws();
                        String k = string();
                        expect(':');
                        m.put(k, value());
                    } while (eat(','));
                    expect('}');
                }
                return m;
            }
            if (c == '[') {
                pos++;
                List<Object> l = new ArrayList<>();
                if (!eat(']')) {
                    do {
                        l.add(value());
                    } while (eat(','));
                    expect(']');
                }
                return l;
            }
            if (c == '"') return string();
            if (s.startsWith("true", pos)) {
                pos += 4;
                return true;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return false;
            }
            if (s.startsWith("null", pos)) {
                pos += 4;
                return NULL;
            }
            int start = pos;
            boolean isFloat = false;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                isFloat |= ".eE".indexOf(s.charAt(pos)) >= 0;
                pos++;
            }
            if (start == pos) throw fail("unexpected character");
            String num = s.substring(start, pos);
            try {
                return isFloat ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw fail("invalid number '" + num + "'");
            }
        }

        String string() {
            if (pos >= s.length() || s.charAt(pos) != '"') throw fail("expected string");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw fail("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) throw fail("unterminated string");
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw fail("bad escape");
                        int cp = Integer.parseInt(s.substring(pos, pos + 4), 16);
                        pos += 4;
                        if (cp >= 0xd800 && cp <= 0xdbff && s.startsWith("\\u", pos)) {
                            int lo = Integer.parseInt(s.substring(pos + 2, pos + 6), 16);
                            pos += 6;
                            cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00);
                        }
                        Bytes.appendUtf8(sb, cp);
                    }
                    default -> sb.append(e);
                }
            }
        }
    }

    // ------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("expected a JSON object");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        if (v instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException("expected a JSON array");
    }

    public static String str(Object v) {
        if (v instanceof String s) return s;
        throw new IllegalArgumentException("expected a JSON string");
    }
}
