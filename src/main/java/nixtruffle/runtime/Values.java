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

    /**
     * {@code forceStringNoCtx}: a string that must not have context (Nix rejects store paths in
     * attribute names, file names, regexes, ...).
     */
    public static String stringNoCtx(Object v) {
        Object f = Thunk.force(v);
        if (f instanceof String s) return s;
        if (f instanceof NixString s) throw hasContext(s);
        throw NixException.typeError(f, "a string", null);
    }

    @TruffleBoundary
    private static NixException hasContext(NixString s) {
        String c = s.context[0];
        String path = c.startsWith("=") ? c.substring(1) : c.startsWith("!") ? c.substring(c.indexOf('!', 1) + 1) : c;
        return NixException.error("the string '" + s.value + "' is not allowed to refer to a store path (such as '" + path + "')", null);
    }

    /** {@code forceFloat}: an integer or a float, as a double. */
    public static double number(Object v) {
        Object f = Thunk.force(v);
        if (f instanceof Double d) return d;
        if (f instanceof Long l) return l;
        throw NixException.typeError(f, "a float", null);
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
        EvalThread ctx = CallDepth.enter(location);
        try {
            return coerceForced(Thunk.force(v), coerceMore, copyToStore, context, location);
        } finally {
            CallDepth.exit(ctx);
        }
    }

    private static String coerceForced(Object f, boolean coerceMore, boolean copyToStore, java.util.Set<String> context, Node location) {
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
            if (f instanceof Double d) return formatFixed(d);
            if (f instanceof Boolean b) return b ? "1" : "";
            if (f instanceof NixNull) return "";
            if (f instanceof NixList l) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < l.items.length; i++) {
                    // Like CppNix, the item is forced one level deeper, by the recursive coercion.
                    sb.append(coerce(l.items[i], true, copyToStore, context, location));
                    Object item = l.forceAt(i);
                    // "!!! not quite correct" in CppNix: no separator after an empty list.
                    if (i < l.items.length - 1 && !(item instanceof NixList inner && inner.size() == 0)) sb.append(' ');
                }
                return sb.toString();
            }
        }
        throw NixException.error("cannot coerce " + typeName(f) + " to a string: " + ValuePrinter.printForError(f), location);
    }

    // ----------------------------------------------------- equality, ordering

    /**
     * CppNix's {@code eqValues} on two list elements or attribute values: both are forced, then
     * the same value slot (a shared thunk, or the very same value) is equal to itself even if it
     * is a function, like CppNix's pointer comparison of {@code Value}s.
     */
    @TruffleBoundary
    public static boolean equal(Object a, Object b) {
        EvalThread ctx = CallDepth.enter(null);
        try {
            Object x = Thunk.force(a);
            Object y = Thunk.force(b);
            return a == b || equalValues(x, y);
        } finally {
            CallDepth.exit(ctx);
        }
    }

    /**
     * {@code ==}: CppNix evaluates both operands into fresh values, so there is no identity
     * shortcut at the top level; functions are never equal to anything.
     */
    @TruffleBoundary
    public static boolean equalTop(Object a, Object b) {
        EvalThread ctx = CallDepth.enter(null);
        try {
            return equalValues(Thunk.force(a), Thunk.force(b));
        } finally {
            CallDepth.exit(ctx);
        }
    }

    private static boolean equalValues(Object x, Object y) {
        if (x instanceof Long l && y instanceof Long m) return l.longValue() == m.longValue();
        if (x instanceof Long l && y instanceof Double d) return (double) l == d;
        if (x instanceof Double d && y instanceof Long l) return d == (double) l;
        if (x instanceof Double d && y instanceof Double e) return d.doubleValue() == e.doubleValue();
        if (NixString.is(x) && NixString.is(y)) return NixString.value(x).equals(NixString.value(y));
        if (x instanceof NixPath p && y instanceof NixPath q) return p.path.equals(q.path);
        if (x instanceof Boolean p && y instanceof Boolean q) return p.booleanValue() == q.booleanValue();
        if (x instanceof NixNull && y instanceof NixNull) return true;
        if (x instanceof NixList l && y instanceof NixList m) {
            if (l.size() != m.size()) return false;
            for (int i = 0; i < l.size(); i++) if (!equal(l.items[i], m.items[i])) return false;
            return true;
        }
        if (x instanceof NixAttrs l && y instanceof NixAttrs m) {
            // Two derivations are equal if their outPaths are.
            if (Derivations.isDerivation(l) && Derivations.isDerivation(m)) {
                Object lo = l.getRaw("outPath");
                Object mo = m.getRaw("outPath");
                if (lo != null && mo != null) return equal(lo, mo);
            }
            if (l.size() != m.size()) return false;
            for (int i = 0; i < l.size(); i++) {
                if (!l.keys[i].equals(m.keys[i]) || !equal(l.values[i], m.values[i])) return false;
            }
            return true;
        }
        if (Foreign.isForeign(x) && Foreign.isForeign(y)) return x == y;
        return false;
    }

    /** CppNix's {@code CompareValues}: {@code <}, {@code builtins.lessThan} and {@code sort}. */
    @TruffleBoundary
    public static boolean lessThan(Object a, Object b, Node location) {
        Object x = Thunk.force(a);
        Object y = Thunk.force(b);
        if (x instanceof Double d && y instanceof Long l) return d < (double) l;
        if (x instanceof Long l && y instanceof Double d) return (double) l < d;
        if (x instanceof Long l && y instanceof Long m) return l < m;
        if (x instanceof Double d && y instanceof Double e) return d < e;
        if (NixString.is(x) && NixString.is(y)) return NixString.value(x).compareTo(NixString.value(y)) < 0;
        if (x instanceof NixPath p && y instanceof NixPath q) return p.path.compareTo(q.path) < 0;
        if (x instanceof NixList l && y instanceof NixList m) {
            for (int i = 0; ; i++) {
                if (i == m.size()) return false;
                if (i == l.size()) return true;
                if (!equal(l.items[i], m.items[i])) return lessThan(l.items[i], m.items[i], location);
            }
        }
        String values = ValuePrinter.printForError(x) + " and " + ValuePrinter.printForError(y);
        if (!typeOf(x).equals(typeOf(y))) {
            throw NixException.error("cannot compare " + typeName(x) + " with " + typeName(y) + "; values are " + values, location);
        }
        throw NixException.error("cannot compare " + typeName(x) + " with " + typeName(y) + "; values of that type are incomparable (values are " + values + ")", location);
    }

    /** A total order for keys ({@code genericClosure}), built from {@link #lessThan}. */
    @TruffleBoundary
    public static int compare(Object a, Object b, Node location) {
        if (lessThan(a, b, location)) return -1;
        if (lessThan(b, a, location)) return 1;
        return 0;
    }

    /**
     * {@code coerceToPath}: a path value, or a string holding an absolute path (canonicalised);
     * {@code __toString} may return either. The string's context goes into {@code context}.
     */
    @TruffleBoundary
    public static String coerceToPath(Object v, java.util.Set<String> context, Node location) {
        Object f = Thunk.force(v);
        if (f instanceof NixPath p) return p.path;
        if (f instanceof NixAttrs a) {
            Object toString = a.get("__toString");
            if (toString != null) return coerceToPath(Apply.apply(toString, a, location), context, location);
        }
        String s = coerce(f, false, false, context, location);
        if (s.isEmpty() || s.charAt(0) != '/') throw NixException.error("string '" + s + "' doesn't represent an absolute path", location);
        return NixPath.canonicalize(s);
    }

    // -------------------------------------------------------- float printing

    /** C's {@code %f} (std::to_string): the exact value, rounded to six decimals, ties to even. */
    @TruffleBoundary
    public static String formatFixed(double d) {
        if (Double.isNaN(d)) return (Double.doubleToRawLongBits(d) < 0 ? "-" : "") + "nan";
        if (Double.isInfinite(d)) return d < 0 ? "-inf" : "inf";
        boolean negative = d < 0 || d == 0 && 1 / d < 0;
        String digits = new java.math.BigDecimal(Math.abs(d)).setScale(6, java.math.RoundingMode.HALF_EVEN).toPlainString();
        return negative ? "-" + digits : digits;
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
