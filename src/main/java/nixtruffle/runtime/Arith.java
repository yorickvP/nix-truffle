package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

/** Generic arithmetic, shared by the operator fallbacks and {@code builtins.add} etc. */
public final class Arith {
    private Arith() {}

    private static boolean isNumber(Object v) { return v instanceof Long || v instanceof Double; }

    @TruffleBoundary
    public static Object add(Object a, Object b, Node location) {
        if (a instanceof Long x && b instanceof Long y) {
            try {
                return Math.addExact(x, y);
            } catch (ArithmeticException e) {
                throw NixException.overflow("adding", x, y, location);
            }
        }
        if (isNumber(a) && isNumber(b)) return ((Number) a).doubleValue() + ((Number) b).doubleValue();
        if (a instanceof String s) {
            if (b instanceof String t) return s + t;
            if (b instanceof NixPath p) return s + p.path;
        }
        if (a instanceof NixPath p) {
            if (b instanceof String t) return new NixPath(NixPath.canonicalize(p.path + t));
            if (b instanceof NixPath q) return new NixPath(NixPath.canonicalize(p.path + q.path));
        }
        throw NixException.error("cannot add " + Values.typeName(b) + " to " + Values.typeName(a), location);
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
