package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

/** Any Nix evaluation error. Subclasses mark the ones {@code builtins.tryEval} may catch. */
public class NixException extends AbstractTruffleException {
    public NixException(String message, Node location) {
        super(message, location);
    }

    /** {@code throw} and failed {@code assert}: catchable by {@code builtins.tryEval}. */
    public static final class Catchable extends NixException {
        public Catchable(String message, Node location) { super(message, location); }
    }

    @TruffleBoundary
    public static NixException error(String message, Node location) {
        return new NixException(message, location);
    }

    @TruffleBoundary
    public static NixException typeError(Object value, String expected, Node location) {
        return new NixException("value is " + Values.typeName(value) + " while " + expected + " was expected", location);
    }

    @TruffleBoundary
    public static NixException infiniteRecursion(Node location) {
        return new NixException("infinite recursion encountered", location);
    }

    @TruffleBoundary
    public static NixException overflow(String verb, long a, long b, Node location) {
        return new NixException("integer overflow in " + verb + " " + a + " " + switch (verb) {
            case "adding" -> "+";
            case "subtracting" -> "-";
            case "multiplying" -> "*";
            default -> "/";
        } + " " + b, location);
    }
}
