package nixtruffle.builtins;

import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** {@code builtins.fromTOML}: a TOML 1.0 parser producing Nix values (timestamps are rejected, like Lix). */
final class Toml {
    private final String s;
    private int pos;

    private Toml(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Toml t = new Toml(text);
        TreeMap<String, Object> root = new TreeMap<>();
        t.document(root);
        return toNix(root);
    }

    /** Tables are TreeMaps, arrays are ArrayLists; arrays of tables are marked so [[x]] can append. */
    private static final class TableArray extends ArrayList<Object> {}

    /** A table created by [header] or [[header]]: must not be re-opened by another [header]. */
    private static final class Defined extends TreeMap<String, Object> {}

    /** A table created implicitly (as a prefix of a dotted key or header); may be defined later. */
    private static final class Implicit extends TreeMap<String, Object> {}

    /** An inline table or a table created by dotted keys in a key/value line: sealed. */
    private static final class Inline extends TreeMap<String, Object> {}

    private static Object toNix(Object v) {
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put((String) e.getKey(), toNix(e.getValue()));
            return NixAttrs.fromMap(out);
        }
        if (v instanceof List<?> l) {
            Object[] items = new Object[l.size()];
            for (int i = 0; i < items.length; i++) items[i] = toNix(l.get(i));
            return new NixList(items);
        }
        return v;
    }

    private NixException fail(String message) {
        int line = 1;
        for (int i = 0; i < Math.min(pos, s.length()); i++) if (s.charAt(i) == '\n') line++;
        return NixException.error("while parsing TOML: " + message + " (line " + line + ")", null);
    }

    private char peek() { return pos < s.length() ? s.charAt(pos) : '\0'; }
    private char peek(int k) { return pos + k < s.length() ? s.charAt(pos + k) : '\0'; }
    private boolean eof() { return pos >= s.length(); }

    private void skipSpaces() {
        while (peek() == ' ' || peek() == '\t') pos++;
    }

    private void skipComment() {
        if (peek() == '#') while (!eof() && peek() != '\n') pos++;
    }

    /** Whitespace, newlines and comments (inside arrays). */
    private void skipAll() {
        while (true) {
            skipSpaces();
            skipComment();
            if (peek() == '\n' || peek() == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private void endOfLine() {
        skipSpaces();
        skipComment();
        if (peek() == '\r') pos++;
        if (!eof() && peek() != '\n') throw fail("expected end of line, found '" + peek() + "'");
        if (!eof()) pos++;
    }

    @SuppressWarnings("unchecked")
    private void document(TreeMap<String, Object> root) {
        TreeMap<String, Object> current = root;
        while (true) {
            skipAll();
            if (eof()) return;
            if (peek() == '[') {
                boolean array = peek(1) == '[';
                pos += array ? 2 : 1;
                skipSpaces();
                List<String> keys = key();
                skipSpaces();
                if (peek() != ']' || array && peek(1) != ']') throw fail("expected ']' after table header");
                pos += array ? 2 : 1;
                endOfLine();
                TreeMap<String, Object> parent = root;
                for (int i = 0; i < keys.size() - 1; i++) parent = descend(parent, keys.get(i));
                String last = keys.get(keys.size() - 1);
                Object existing = parent.get(last);
                if (array) {
                    TableArray arr;
                    if (existing == null) {
                        arr = new TableArray();
                        parent.put(last, arr);
                    } else if (existing instanceof TableArray ta) {
                        arr = ta;
                    } else {
                        throw fail("key '" + last + "' is already defined");
                    }
                    Defined table = new Defined();
                    arr.add(table);
                    current = table;
                } else {
                    if (existing == null) {
                        Defined table = new Defined();
                        parent.put(last, table);
                        current = table;
                    } else if (existing instanceof Implicit imp) {
                        Defined table = new Defined();
                        table.putAll(imp);
                        parent.put(last, table);
                        current = table;
                    } else {
                        throw fail("table '" + String.join(".", keys) + "' is already defined");
                    }
                }
            } else {
                keyValue(current);
                endOfLine();
            }
        }
    }

    /** Walks into a header prefix: creating implicit tables, entering the last element of table arrays. */
    @SuppressWarnings("unchecked")
    private TreeMap<String, Object> descend(TreeMap<String, Object> parent, String key) {
        Object v = parent.get(key);
        if (v == null) {
            Implicit t = new Implicit();
            parent.put(key, t);
            return t;
        }
        if (v instanceof TableArray arr) return (TreeMap<String, Object>) arr.get(arr.size() - 1);
        if (v instanceof Inline) throw fail("cannot extend inline table '" + key + "'");
        if (v instanceof TreeMap<?, ?> m) return (TreeMap<String, Object>) m;
        throw fail("key '" + key + "' is not a table");
    }

    @SuppressWarnings("unchecked")
    private void keyValue(TreeMap<String, Object> table) {
        List<String> keys = key();
        skipSpaces();
        if (peek() != '=') throw fail("expected '=' after key");
        pos++;
        skipSpaces();
        Object value = value();
        TreeMap<String, Object> t = table;
        for (int i = 0; i < keys.size() - 1; i++) {
            Object v = t.get(keys.get(i));
            if (v == null) {
                Inline sub = new Inline();
                t.put(keys.get(i), sub);
                t = sub;
            } else if (v instanceof Inline sub) {
                t = sub;
            } else if (v instanceof Implicit sub) {
                t = sub;
            } else {
                throw fail("key '" + keys.get(i) + "' is already defined");
            }
        }
        String last = keys.get(keys.size() - 1);
        if (t.containsKey(last)) throw fail("key '" + last + "' is already defined");
        t.put(last, value);
    }

    private List<String> key() {
        List<String> keys = new ArrayList<>();
        while (true) {
            skipSpaces();
            char c = peek();
            if (c == '"') {
                keys.add(basicString());
            } else if (c == '\'') {
                keys.add(literalString());
            } else {
                int start = pos;
                while (Character.isLetterOrDigit(peek()) && peek() < 128 || peek() == '_' || peek() == '-') pos++;
                if (start == pos) throw fail("expected a key");
                keys.add(s.substring(start, pos));
            }
            skipSpaces();
            if (peek() != '.') return keys;
            pos++;
        }
    }

    private Object value() {
        char c = peek();
        if (c == '"') return s.startsWith("\"\"\"", pos) ? multilineBasicString() : basicString();
        if (c == '\'') return s.startsWith("'''", pos) ? multilineLiteralString() : literalString();
        if (c == '[') return array();
        if (c == '{') return inlineTable();
        if (s.startsWith("true", pos)) {
            pos += 4;
            return true;
        }
        if (s.startsWith("false", pos)) {
            pos += 5;
            return false;
        }
        return number();
    }

    private List<Object> array() {
        pos++;
        List<Object> items = new ArrayList<>();
        while (true) {
            skipAll();
            if (peek() == ']') {
                pos++;
                return items;
            }
            items.add(value());
            skipAll();
            if (peek() == ',') {
                pos++;
            } else if (peek() != ']') {
                throw fail("expected ',' or ']' in array");
            }
        }
    }

    private Inline inlineTable() {
        pos++;
        Inline table = new Inline();
        skipSpaces();
        if (peek() == '}') {
            pos++;
            return table;
        }
        while (true) {
            skipSpaces();
            keyValue(table);
            skipSpaces();
            if (peek() == ',') {
                pos++;
            } else if (peek() == '}') {
                pos++;
                return table;
            } else {
                throw fail("expected ',' or '}' in inline table");
            }
        }
    }

    private Object number() {
        int start = pos;
        while (!eof() && " \t\r\n,]}#".indexOf(peek()) < 0) pos++;
        String raw = s.substring(start, pos);
        if (raw.matches("\\d{4}-\\d{2}-\\d{2}.*") || raw.matches("\\d{2}:\\d{2}.*")) throw fail("Dates and times are not supported");
        String t = raw.replace("_", "");
        try {
            switch (t) {
                case "inf", "+inf" -> { return Double.POSITIVE_INFINITY; }
                case "-inf" -> { return Double.NEGATIVE_INFINITY; }
                case "nan", "+nan", "-nan" -> { return Double.NaN; }
                default -> {}
            }
            if (t.startsWith("0x")) return Long.parseLong(t.substring(2), 16);
            if (t.startsWith("0o")) return Long.parseLong(t.substring(2), 8);
            if (t.startsWith("0b")) return Long.parseLong(t.substring(2), 2);
            if (t.matches("[+-]?\\d+")) return Long.parseLong(t);
            if (t.matches("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            // fall through
        }
        throw fail("invalid value '" + raw + "'");
    }

    private String basicString() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (eof() || peek() == '\n') throw fail("unterminated string");
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') escape(sb); else sb.append(c);
        }
    }

    private void escape(StringBuilder sb) {
        char e = s.charAt(pos++);
        switch (e) {
            case 'b' -> sb.append('\b');
            case 't' -> sb.append('\t');
            case 'n' -> sb.append('\n');
            case 'f' -> sb.append('\f');
            case 'r' -> sb.append('\r');
            case 'e' -> sb.append('\u001b');
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case 'u' -> {
                sb.appendCodePoint(Integer.parseInt(s.substring(pos, pos + 4), 16));
                pos += 4;
            }
            case 'U' -> {
                sb.appendCodePoint(Integer.parseInt(s.substring(pos, pos + 8), 16));
                pos += 8;
            }
            default -> throw fail("invalid escape '\\" + e + "'");
        }
    }

    private String multilineBasicString() {
        pos += 3;
        if (peek() == '\r') pos++;
        if (peek() == '\n') pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (eof()) throw fail("unterminated string");
            if (s.startsWith("\"\"\"", pos)) {
                // The delimiter is the last three of a run of up to five quotes.
                int q = 0;
                while (q < 5 && peek(q) == '"') q++;
                sb.append("\"".repeat(q - 3));
                pos += q;
                return sb.toString();
            }
            char c = s.charAt(pos++);
            if (c == '\\') {
                int save = pos;
                while (peek() == ' ' || peek() == '\t') pos++;
                if (peek() == '\n' || peek() == '\r') {
                    // Line-ending backslash: trim the newline and following whitespace.
                    while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') pos++;
                } else {
                    pos = save;
                    escape(sb);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private String literalString() {
        pos++;
        int start = pos;
        while (!eof() && peek() != '\'' && peek() != '\n') pos++;
        if (peek() != '\'') throw fail("unterminated string");
        return s.substring(start, pos++);
    }

    private String multilineLiteralString() {
        pos += 3;
        if (peek() == '\r') pos++;
        if (peek() == '\n') pos++;
        int end = s.indexOf("'''", pos);
        if (end < 0) throw fail("unterminated string");
        while (end + 3 < s.length() && s.charAt(end + 3) == '\'') end++;
        String out = s.substring(pos, end);
        pos = end + 3;
        return out;
    }
}
