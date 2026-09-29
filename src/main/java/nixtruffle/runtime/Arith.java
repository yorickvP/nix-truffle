package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

/** Generic arithmetic, shared by the operator fallbacks and {@code builtins.add} etc. */
public final class Arith {
    private Arith() {}

    private static boolean isNumber(Object v) { return v instanceof Long || v instanceof Double; }

    /**
     * {@code a + b}, following CppNix's ExprConcatStrings: the left operand's type decides. Numbers
     * add; a path absorbs the right side as a path; anything else concatenates as strings, copying
     * paths to the store only if the left side is a string.
     */
    @TruffleBoundary
    public static Object add(Object a, Object b, Node location) {
        if (a instanceof Long x) {
            if (b instanceof Long y) {
                try {
                    return Math.addExact(x, y);
                } catch (ArithmeticException e) {
                    throw NixException.overflow("adding", x, y, location);
                }
            }
            if (b instanceof Double y) return x + y;
            throw NixException.error("cannot add " + Values.typeName(b) + " to an integer", location);
        }
        if (a instanceof Double x) {
            if (isNumber(b)) return x + ((Number) b).doubleValue();
            throw NixException.error("cannot add " + Values.typeName(b) + " to a float", location);
        }
        if (a instanceof NixPath p) {
            java.util.Set<String> context = new java.util.TreeSet<>();
            String rest = Values.coerce(b, false, false, context, location);
            if (!context.isEmpty()) throw NixException.error("a string that refers to a store path cannot be appended to a path", location);
            return new NixPath(NixPath.canonicalize(p.path + rest));
        }
        java.util.Set<String> context = new java.util.TreeSet<>();
        boolean copy = NixString.is(a);
        String left = Values.coerce(a, false, copy, context, location);
        String right = Values.coerce(b, false, copy, context, location);
        return NixString.make(left + right, context);
    }

    @TruffleBoundary
    public static Object sub(Object a, Object b, Node location) {
        checkNumbers(a, b, location);
        if (a instanceof Long x && b instanceof Long y) {
            try {
                return Math.subtractExact(x, y);
            } catch (ArithmeticException e) {
                throw NixException.overflow("subtracting", x, y, location);
            }
        }
        return ((Number) a).doubleValue() - ((Number) b).doubleValue();
    }

    @TruffleBoundary
    public static Object mul(Object a, Object b, Node location) {
        checkNumbers(a, b, location);
        if (a instanceof Long x && b instanceof Long y) {
            try {
                return Math.multiplyExact(x, y);
            } catch (ArithmeticException e) {
                throw NixException.overflow("multiplying", x, y, location);
            }
        }
        return ((Number) a).doubleValue() * ((Number) b).doubleValue();
    }

    @TruffleBoundary
    public static Object div(Object a, Object b, Node location) {
        checkNumbers(a, b, location);
        if (a instanceof Long x && b instanceof Long y) return divLong(x, y, location);
        double d = ((Number) b).doubleValue();
        if (d == 0) throw NixException.error("division by zero", location);
        return ((Number) a).doubleValue() / d;
    }

    public static long divLong(long a, long b, Node location) {
        if (b == 0) throw NixException.error("division by zero", location);
        if (a == Long.MIN_VALUE && b == -1) throw NixException.overflow("dividing", a, b, location);
        return a / b;
    }

    private static void checkNumbers(Object a, Object b, Node location) {
        if (!isNumber(a)) throw NixException.typeError(a, "an integer", location);
        if (!isNumber(b)) throw NixException.typeError(b, "an integer", location);
    }
}
