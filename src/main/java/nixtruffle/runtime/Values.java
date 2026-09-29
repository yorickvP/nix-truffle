package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

import java.util.Locale;

/** Type names, forcing helpers, coercions, equality and ordering. */
public final class Values {
    private Values() {}

    @TruffleBoundary
    public static String typeName(Object v) {
        if (v instanceof Long) return "an integer";
        if (v instanceof Double) return "a float";
        if (v instanceof Boolean) return "a Boolean";
        if (v instanceof String || v instanceof NixString) return "a string";
        if (v instanceof NixPath) return "a path";
        if (v instanceof NixNull) return "null";
        if (v instanceof NixAttrs) return "a set";
        if (v instanceof NixList) return "a list";
        if (v instanceof NixLambda) return "a function";
        if (v instanceof Builtin) return "a built-in function";
        if (v instanceof PartialApp) return "a partially applied built-in function";
        if (v instanceof Thunk) return "a thunk";
        return "a foreign value";
    }

    /** The result of {@code builtins.typeOf}. */
    @TruffleBoundary
    public static String typeOf(Object v) {
        if (v instanceof Long) return "int";
        if (v instanceof Double) return "float";
        if (v instanceof Boolean) return "bool";
        if (v instanceof String || v instanceof NixString) return "string";
        if (v instanceof NixPath) return "path";
        if (v instanceof NixNull) return "null";
        if (v instanceof NixAttrs) return "set";
        if (v instanceof NixList) return "list";
        if (v instanceof NixFunction) return "lambda";
        if (Foreign.asArray(v) != null) return "list";
        if (Foreign.isExecutable(v)) return "lambda";
        if (Foreign.hasMembers(v)) return "set";
        return "foreign";
    }

    // ---------------------------------------------------------------- forcing

    public static NixAttrs attrs(Object v) {
        Object f = Thunk.force(v);
        if (f instanceof NixAttrs a) return a;
        if (Foreign.isForeign(f) && Foreign.hasMembers(f)) return Foreign.asAttrs(f);
        throw NixException.typeError(f, "a set", null);
    }

    public static Object[] list(Object v) {
        Object f = Thunk.force(v);
        if (f instanceof NixList l) return l.items;
        if (Foreign.isForeign(f)) {
            Object[] items = Foreign.asArray(f);
            if (items != null) return items;
        }
        throw NixException.typeError(f, "a list", null);
    }

    /** The string value, discarding any context. */
    public static String string(Object v) {
        Object f = Thunk.force(v);
        if (f instanceof String s) return s;
        if (f instanceof NixString s) return s.value;
        throw NixException.typeError(f, "a string", null);
    }

    /** The string value; its context is added to {@code context}. */
    public static String string(Object v, java.util.Set<String> context) {
        Object f = Thunk.force(v);
        NixString.addContext(f, context);
        return string(f);
    }

    public static long integer(Object v) {
        Object f = Thunk.force(v);
        if (f instanceof Long l) return l;
        throw NixException.typeError(f, "an integer", null);
    }

    public static boolean bool(Object v) {
        Object f = Thunk.force(v);
        if (f instanceof Boolean b) return b;
        throw NixException.typeError(f, "a Boolean", null);
    }

    public static Object function(Object v) {
        Object f = Thunk.force(v);
        if (f instanceof NixFunction || f instanceof NixAttrs a && a.getRaw("__functor") != null) return f;
        if (Foreign.isForeign(f) && Foreign.isExecutable(f)) return f;
        throw NixException.typeError(f, "a function", null);
    }

    // -------------------------------------------------------------- coercions

    /** Coercion without context tracking: {@code toStringMode} is {@code builtins.toString}'s rules. */
    public static String coerceToString(Object v, boolean toStringMode, Node location) {
        return coerce(v, toStringMode, false, null, location);
    }

    /**
     * Port of CppNix's {@code coerceToString}. {@code coerceMore} also accepts numbers, booleans,
     * null and lists ({@code toString}, derivation attributes); {@code copyToStore} turns paths into
     * store paths (interpolation, derivation attributes). Context goes into {@code context}.
     */
    @TruffleBoundary
    public static String coerce(Object v, boolean coerceMore, boolean copyToStore, java.util.Set<String> context, Node location) {
        Object f = Thunk.force(v);
        if (f instanceof String s) return s;
        if (f instanceof NixString s) {
            NixString.addContext(s, context);
            return s.value;
        }
        if (f instanceof NixPath p) {
            if (!copyToStore) return p.path;
            String storePath = nixtruffle.NixContext.get(null).copyPathToStore(p.path, location);
            if (context != null) context.add(storePath);
            return storePath;
        }
        if (f instanceof NixAttrs a) {
            Object toString = a.get("__toString");
            if (toString != null) return coerce(Apply.apply(toString, a, location), coerceMore, copyToStore, context, location);
            Object outPath = a.get("outPath");
            if (outPath != null) return coerce(outPath, coerceMore, copyToStore, context, location);
        }
        if (coerceMore) {
            if (f instanceof Long l) return Long.toString(l);
            if (f instanceof Double d) return String.format(Locale.ROOT, "%.6f", d);
            if (f instanceof Boolean b) return b ? "1" : "";
            if (f instanceof NixNull) return "";
            if (f instanceof NixList l) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < l.items.length; i++) {
                    Object item = l.forceAt(i);
                    sb.append(coerce(item, true, copyToStore, context, location));
                    // "!!! not quite correct" in CppNix: no separator after an empty list.
                    if (i < l.items.length - 1 && !(item instanceof NixList inner && inner.size() == 0)) sb.append(' ');
                }
                return sb.toString();
            }
        }
        throw NixException.error("cannot coerce " + typeName(f) + " to a string", location);
    }

    // ----------------------------------------------------- equality, ordering

    @TruffleBoundary
    public static boolean equal(Object a, Object b) {
        Object x = Thunk.force(a);
        Object y = Thunk.force(b);
        if (x == y) return true; // CppNix: identical values are equal, even functions
        if (x instanceof Long l && y instanceof Long m) return l.longValue() == m.longValue();
        if ((x instanceof Long || x instanceof Double) && (y instanceof Long || y instanceof Double)) {
            return ((Number) x).doubleValue() == ((Number) y).doubleValue();
        }
        if (NixString.is(x) && NixString.is(y)) return NixString.value(x).equals(NixString.value(y));
        if (x instanceof NixPath p && y instanceof NixPath q) return p.path.equals(q.path);
        if (x instanceof Boolean p && y instanceof Boolean q) return p.booleanValue() == q.booleanValue();
        if (x instanceof NixNull && y instanceof NixNull) return true;
        if (x instanceof NixList l && y instanceof NixList m) {
            if (l.size() != m.size()) return false;
            for (int i = 0; i < l.size(); i++) if (!equal(l.forceAt(i), m.forceAt(i))) return false;
            return true;
        }
        if (x instanceof NixAttrs l && y instanceof NixAttrs m) {
            if (l.size() != m.size()) return false;
            for (int i = 0; i < l.size(); i++) {
                if (!l.keys[i].equals(m.keys[i])) return false;
            }
            for (int i = 0; i < l.size(); i++) if (!equal(l.forceAt(i), m.forceAt(i))) return false;
            return true;
        }
        return false;
    }

    /** Ordering used by {@code <} and {@code builtins.lessThan}/{@code sort}. */
    @TruffleBoundary
    public static int compare(Object a, Object b, Node location) {
        Object x = Thunk.force(a);
        Object y = Thunk.force(b);
        if (x instanceof Long l && y instanceof Long m) return Long.compare(l, m);
        if ((x instanceof Long || x instanceof Double) && (y instanceof Long || y instanceof Double)) {
            return Double.compare(((Number) x).doubleValue(), ((Number) y).doubleValue());
        }
        if (NixString.is(x) && NixString.is(y)) return NixString.value(x).compareTo(NixString.value(y));
        if (x instanceof NixPath p && y instanceof NixPath q) return p.path.compareTo(q.path);
        if (x instanceof NixList l && y instanceof NixList m) {
            for (int i = 0; ; i++) {
                if (i == m.size()) return i == l.size() ? 0 : 1;
                if (i == l.size()) return -1;
                if (!equal(l.forceAt(i), m.forceAt(i))) return compare(l.items[i], m.items[i], location);
            }
        }
        throw NixException.error("cannot compare " + typeName(x) + " with " + typeName(y), location);
    }

    // -------------------------------------------------------- float printing

    /** C's {@code %g} (what Nix uses to print floats). */
    @TruffleBoundary
    public static String formatFloat(double v) {
        if (Double.isNaN(v)) return v < 0 ? "-nan" : "nan";
        if (Double.isInfinite(v)) return v < 0 ? "-inf" : "inf";
        if (v == 0) return (1 / v < 0) ? "-0" : "0";
        String e = String.format(Locale.ROOT, "%.5e", v);
        int ei = e.indexOf('e');
        int exp = Integer.parseInt(e.substring(ei + 1));
        if (exp < -4 || exp >= 6) {
            String mantissa = stripZeros(e.substring(0, ei));
            String expDigits = String.valueOf(Math.abs(exp));
            if (expDigits.length() < 2) expDigits = "0" + expDigits;
            return mantissa + "e" + (exp < 0 ? "-" : "+") + expDigits;
        }
        return stripZeros(String.format(Locale.ROOT, "%." + (5 - exp) + "f", v));
    }

    private static String stripZeros(String s) {
        if (s.indexOf('.') < 0) return s;
        int end = s.length();
        while (s.charAt(end - 1) == '0') end--;
        if (s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }
}
