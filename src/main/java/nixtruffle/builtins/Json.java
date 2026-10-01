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

    /**
     * CppNix's printValueAsJSON; paths are copied to the store if {@code copyToStore}. Like
     * nlohmann::json, strings must be valid UTF-8, which is checked once the whole value has been
     * evaluated (evaluation errors come first).
     */
    static String toJSON(Object value, java.util.Set<String> context, boolean copyToStore) {
        return toJSON(value, context, copyToStore, false);
    }

    /** With {@code ahead}, workers evaluate the parts that are about to be written (see {@link nixtruffle.runtime.Parallel}). */
    static String toJSON(Object value, java.util.Set<String> context, boolean copyToStore, boolean ahead) {
        StringBuilder sb = new StringBuilder();
        String[] invalid = {null};
        write(value, sb, context, copyToStore, invalid, ahead);
        if (invalid[0] != null) throw NixException.error("JSON serialization error: [json.exception.type_error.316] " + invalid[0], null);
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

    /** Every value is a level of {@code max-call-depth}, forced inside it (like CppNix). */
    private static void write(Object value, StringBuilder sb, java.util.Set<String> context, boolean copyToStore, String[] invalid, boolean ahead) {
        nixtruffle.runtime.EvalThread ctx = nixtruffle.runtime.CallDepth.enter(null);
        try {
            writeForced(Thunk.force(value), sb, context, copyToStore, invalid, ahead);
        } finally {
            nixtruffle.runtime.CallDepth.exit(ctx);
        }
    }

    private static void writeForced(Object v, StringBuilder sb, java.util.Set<String> context, boolean copyToStore, String[] invalid, boolean ahead) {
        switch (v) {
            case NixNull n -> sb.append("null");
            case Boolean b -> sb.append(b);
            case Long l -> sb.append(l);
            case Double d -> sb.append(formatDouble(d));
            case String s -> quote(s, sb, invalid);
            case NixString s -> {
                NixString.addContext(s, context);
                quote(s.value, sb, invalid);
            }
            case NixPath p -> quote(Values.coerce(p, false, copyToStore, context, null), sb, invalid);
            case NixList l -> {
                if (ahead) nixtruffle.runtime.Parallel.ahead(l.items);
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(',');
                    write(l.items[i], sb, context, copyToStore, invalid, ahead);
                }
                sb.append(']');
            }
            case NixAttrs a -> {
                if (a.getRaw("__toString") != null) {
                    quote(Values.coerce(a, false, false, context, null), sb, invalid);
                    return;
                }
                if (a.getRaw("outPath") != null) {
                    write(a.getRaw("outPath"), sb, context, copyToStore, invalid, ahead);
                    return;
                }
                if (ahead) nixtruffle.runtime.Parallel.ahead(a.values);
                sb.append('{');
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) sb.append(',');
                    quote(a.keys[i], sb, invalid);
                    sb.append(':');
                    write(a.values[i], sb, context, copyToStore, invalid, ahead);
                }
                sb.append('}');
            }
            case NixFunction f -> throw NixException.error("cannot convert a function to JSON", null);
            default -> {
                Object[] items = Foreign.isForeign(v) ? Foreign.asArray(v) : null;
                if (items != null) {
                    write(new NixList(items), sb, context, copyToStore, invalid, ahead);
                } else if (Foreign.isForeign(v) && Foreign.hasMembers(v)) {
                    write(Foreign.asAttrs(v), sb, context, copyToStore, invalid, ahead);
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

    /** A JSON string as nlohmann::json dumps it; the first invalid UTF-8 is recorded in {@code invalid}. */
    static void quote(String s, StringBuilder sb, String[] invalid) {
        if (invalid[0] == null) {
            int bad = nixtruffle.runtime.Bytes.utf8Error(s);
            if (bad >= 0) invalid[0] = "invalid UTF-8 byte at index " + bad + ": 0x" + String.format("%02X", (int) s.charAt(bad));
        }
        quote(s, sb);
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

    /** {@code builtins.fromJSON}: a strict RFC 8259 parser, like nlohmann::json's. */
    static Object fromJSON(String text) {
        Reader r = new Reader(text);
        r.ws();
        Object v = r.value();
        r.ws();
        if (r.pos != text.length()) throw r.fail("syntax error: unexpected trailing input");
        return v;
    }

    private static final class Reader {
        final String s;
        int pos;

        Reader(String s) { this.s = s; }

        NixException fail(String message) {
            return NixException.error("[json.exception.parse_error.101] parse error at byte " + (pos + 1) + ": " + message, null);
        }

        void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
                pos++;
            }
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
                        noNul(key);
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
            if (c == '"') {
                String str = string();
                noNul(str);
                return str;
            }
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
            return number();
        }

        private void noNul(String str) {
            if (str.indexOf('\0') >= 0) throw NixException.error("input string contains a NUL byte", null);
        }

        /** JSON's number grammar: {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}. */
        private Object number() {
            int start = pos;
            if (at() == '-') pos++;
            if (at() == '0') {
                pos++;
            } else if (at() >= '1' && at() <= '9') {
                while (at() >= '0' && at() <= '9') pos++;
            } else {
                throw fail("invalid literal");
            }
            boolean isFloat = false;
            if (at() == '.') {
                isFloat = true;
                pos++;
                if (!(at() >= '0' && at() <= '9')) throw fail("invalid number");
                while (at() >= '0' && at() <= '9') pos++;
            }
            if (at() == 'e' || at() == 'E') {
                isFloat = true;
                pos++;
                if (at() == '+' || at() == '-') pos++;
                if (!(at() >= '0' && at() <= '9')) throw fail("invalid number");
                while (at() >= '0' && at() <= '9') pos++;
            }
            String num = s.substring(start, pos);
            if (!isFloat) {
                try {
                    return Long.parseLong(num);
                } catch (NumberFormatException e) {
                    // Too big for an int64: an unsigned 64-bit value is out of range for Nix,
                    // anything bigger becomes a float (like nlohmann::json).
                    if (!num.startsWith("-") && new java.math.BigInteger(num).bitLength() <= 64) {
                        throw NixException.error("unsigned json number " + num + " outside of Nix integer range", null);
                    }
                }
            }
            return Double.parseDouble(num);
        }

        private char at() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        /** A string literal; the bytes must be valid UTF-8, and escapes produce UTF-8. */
        String string() {
            if (pos >= s.length() || s.charAt(pos) != '"') throw fail("expected string");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw fail("missing closing quote");
                char c = s.charAt(pos);
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c < 0x20) throw fail("control character must be escaped");
                if (c >= 0x80) {
                    // Copy one UTF-8 sequence, which must be valid.
                    int len = c >= 0xf0 ? 4 : c >= 0xe0 ? 3 : 2;
                    String seq = s.substring(pos, Math.min(s.length(), pos + len));
                    if (seq.length() != len || nixtruffle.runtime.Bytes.utf8Error(seq) >= 0) throw fail("invalid UTF-8 byte");
                    sb.append(seq);
                    pos += len;
                    continue;
                }
                pos++;
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) throw fail("missing closing quote");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        int cp = hex4();
                        if (cp >= 0xd800 && cp <= 0xdbff) {
                            if (!s.startsWith("\\u", pos)) throw fail("surrogate U+D800..U+DBFF must be followed by U+DC00..U+DFFF");
                            pos += 2;
                            int lo = hex4();
                            if (lo < 0xdc00 || lo > 0xdfff) throw fail("surrogate U+D800..U+DBFF must be followed by U+DC00..U+DFFF");
                            cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00);
                        } else if (cp >= 0xdc00 && cp <= 0xdfff) {
                            throw fail("surrogate U+DC00..U+DFFF must follow U+D800..U+DBFF");
                        }
                        nixtruffle.runtime.Bytes.appendUtf8(sb, cp);
                    }
                    default -> throw fail("invalid string: forbidden character after backslash");
                }
            }
        }

        private int hex4() {
            if (pos + 4 > s.length()) throw fail("invalid string: '\\u' must be followed by 4 hex digits");
            int v = 0;
            for (int i = 0; i < 4; i++) {
                int d = Character.digit(s.charAt(pos + i), 16);
                if (d < 0 || s.charAt(pos + i) > 'f') throw fail("invalid string: '\\u' must be followed by 4 hex digits");
                v = v * 16 + d;
            }
            pos += 4;
            return v;
        }
    }
}
