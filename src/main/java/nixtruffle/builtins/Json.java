package nixtruffle.builtins;

import nixtruffle.runtime.Foreign;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixFunction;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.NixNull;
import nixtruffle.runtime.NixPath;
import nixtruffle.runtime.NixString;
import nixtruffle.runtime.Thunk;
import nixtruffle.runtime.Values;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** {@code builtins.toJSON} / {@code builtins.fromJSON}. */
final class Json {
    private Json() {}

    /** {@code builtins.toJSON}: the result carries the context of all strings inside. */
    static Object toJSON(Object value) {
        java.util.Set<String> context = new java.util.TreeSet<>();
        return NixString.make(toJSON(value, context, true), context);
    }

    /** CppNix's printValueAsJSON; paths are copied to the store if {@code copyToStore}. */
    static String toJSON(Object value, java.util.Set<String> context, boolean copyToStore) {
        StringBuilder sb = new StringBuilder();
        write(Thunk.force(value), sb, context, copyToStore);
        return sb.toString();
    }

    /** A JSON object from already-encoded members (sorted by key, like nlohmann::json). */
    static String object(java.util.SortedMap<String, String> members) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : members.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            quote(e.getKey(), sb);
            sb.append(':').append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static void write(Object v, StringBuilder sb, java.util.Set<String> context, boolean copyToStore) {
        switch (v) {
            case NixNull n -> sb.append("null");
            case Boolean b -> sb.append(b);
            case Long l -> sb.append(l);
            case Double d -> sb.append(formatDouble(d));
            case String s -> quote(s, sb);
            case NixString s -> {
                NixString.addContext(s, context);
                quote(s.value, sb);
            }
            case NixPath p -> quote(Values.coerce(p, false, copyToStore, context, null), sb);
            case NixList l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(',');
                    write(l.forceAt(i), sb, context, copyToStore);
                }
                sb.append(']');
            }
            case NixAttrs a -> {
                if (a.getRaw("__toString") != null) {
                    quote(Values.coerce(a, false, false, context, null), sb);
                    return;
                }
                if (a.getRaw("outPath") != null) {
                    write(a.get("outPath"), sb, context, copyToStore);
                    return;
                }
                sb.append('{');
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) sb.append(',');
                    quote(a.keys[i], sb);
                    sb.append(':');
                    write(a.forceAt(i), sb, context, copyToStore);
                }
                sb.append('}');
            }
            case NixFunction f -> throw NixException.error("cannot convert a function to JSON", null);
            default -> {
                Object[] items = Foreign.isForeign(v) ? Foreign.asArray(v) : null;
                if (items != null) {
                    write(new NixList(items), sb, context, copyToStore);
                } else if (Foreign.isForeign(v) && Foreign.hasMembers(v)) {
                    write(Foreign.asAttrs(v), sb, context, copyToStore);
                } else {
                    throw NixException.error("cannot convert " + Values.typeName(v) + " to JSON", null);
                }
            }
        }
    }

    /** nlohmann::json's double formatting: shortest round-trip digits, "1.0", "1e+20", "1.5e-07". */
    static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == 0) return 1 / d < 0 ? "-0.0" : "0.0";
        java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = bd.unscaledValue().toString();
        int k = digits.length();
        int n = k - bd.scale();
        StringBuilder sb = new StringBuilder(d < 0 ? "-" : "");
        if (k <= n && n <= 15) {
            sb.append(digits).append("0".repeat(n - k)).append(".0");
        } else if (0 < n && n <= 15) {
            sb.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-4 < n && n <= 0) {
            sb.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            sb.append(digits.charAt(0));
            if (k > 1) sb.append('.').append(digits, 1, k);
            int e = n - 1;
            sb.append('e').append(e < 0 ? '-' : '+');
            if (Math.abs(e) < 10) sb.append('0');
            sb.append(Math.abs(e));
        }
        return sb.toString();
    }

    static void quote(String s, StringBuilder sb) {
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
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    static Object fromJSON(String text) {
        Reader r = new Reader(text);
        Object v = r.value();
        r.ws();
        if (r.pos != text.length()) throw r.fail("trailing characters");
        return v;
    }

    private static final class Reader {
        final String s;
        int pos;

        Reader(String s) { this.s = s; }

        NixException fail(String message) {
            return NixException.error("error parsing JSON at offset " + pos + ": " + message, null);
        }

        void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
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
                TreeMap<String, Object> map = new TreeMap<>();
                if (!eat('}')) {
                    do {
                        ws();
                        String key = string();
                        expect(':');
                        map.put(key, value());
                    } while (eat(','));
                    expect('}');
                }
                return NixAttrs.fromMap(map);
            }
            if (c == '[') {
                pos++;
                List<Object> items = new ArrayList<>();
                if (!eat(']')) {
                    do {
                        items.add(value());
                    } while (eat(','));
                    expect(']');
                }
                return new NixList(items.toArray());
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
                return NixNull.INSTANCE;
            }
            int start = pos;
            boolean isFloat = false;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                isFloat |= ".eE".indexOf(s.charAt(pos)) >= 0;
                pos++;
            }
            if (start == pos) throw fail("unexpected character '" + c + "'");
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
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(e);
                }
            }
        }
    }
}
