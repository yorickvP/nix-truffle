package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.nodes.Node;

/**
 * CppNix's {@code max-call-depth}. Function calls, and the recursion of comparisons, string
 * coercion, {@code deepSeq} and {@code toJSON}/{@code toXML}, nest a depth that may not go beyond
 * the setting (default 10000): deep recursion fails with "stack overflow; max-call-depth
 * exceeded" (which {@code tryEval} doesn't catch) long before the stack runs out. Operations that
 * CppNix counts but that don't nest (arithmetic and comparisons, which are primop calls there)
 * only {@link #check}.
 */
public final class CallDepth {
    private CallDepth() {}

    /** Enters a level (CppNix's {@code addCallDepth}); leave it with {@link #exit}. */
    public static EvalThread enter(Node location) {
        EvalThread t = EvalThread.current(location);
        if (t.callDepth > t.maxCallDepth) throw overflow(location);
        t.callDepth++;
        return t;
    }

    public static void exit(EvalThread t) {
        t.callDepth--;
    }

    /** What entering a level would check, for operations that don't nest. */
    public static void check(Node location) {
        EvalThread t = EvalThread.current(location);
        if (t.callDepth > t.maxCallDepth) throw overflow(location);
    }

    private static NixException overflow(Node location) {
        CompilerDirectives.transferToInterpreter();
        return new NixException("stack overflow; max-call-depth exceeded", location);
    }
}
