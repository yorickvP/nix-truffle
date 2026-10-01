package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.Node;
import nixtruffle.nodes.LazyCode;

/**
 * A lazy update cell, modelled after thc's {@code Thunk}: code plus its captured environment, a
 * small state machine, and the memoized answer. An evaluation keeps millions of these, so they
 * have just two fields (16 bytes):
 *
 * <ul>
 *   <li>pending: {@code code} is the body to run, a {@link RootCallTarget} or the {@link LazyCode}
 *       of a source thunk (built when first run), and {@code data} the environment it closes over
 *       (or {@code [f, args...]} for builtin applications);
 *   <li>blackhole: being evaluated; re-entering it means the value depends on itself, which Nix
 *       reports as "infinite recursion encountered";
 *   <li>done: {@code data} is the WHNF answer, and the code and environment are released (thc's
 *       "evaluated thunk releases its own references").
 * </ul>
 *
 * If evaluation throws, the thunk goes back to pending (like CppNix), so {@code builtins.tryEval}
 * and later forces re-run the computation and see the same error again.
 *
 * <p>The fast path lives in {@link nixtruffle.nodes.ForceNode}, which inline-caches the code and
 * calls it through a {@code DirectCallNode} so Graal can inline thunk bodies into force sites.
 * {@link #forceSlow} is for runtime code (builtins, printing, interop).
 */
public final class Thunk {
    private static final Object BLACKHOLE = new Object();
    private static final Object DONE = new Object();

    private Object code;
    private Object data;

    public Thunk(Object code, Object env) {
        this.code = code;
        this.data = env;
    }

    public boolean isDone() { return code == DONE; }
    /** The answer, once done. */
    public Object getValue() { return data; }
    /** The body to run, while pending (see {@link #target}); a marker otherwise. */
    public Object getCode() { return code; }
    /** The environment, while pending. */
    public Object getEnv() { return data; }

    /** Whether {@code code} is something to run (not the blackhole or done marker). */
    public static boolean isRunnable(Object code) {
        return code instanceof RootCallTarget || code instanceof LazyCode;
    }

    /** The call target of runnable {@code code}. */
    public static RootCallTarget target(Object code) {
        if (code instanceof LazyCode l) {
            RootCallTarget t = l.built();
            return t != null ? t : l.target();
        }
        return (RootCallTarget) code;
    }

    /** Starts evaluating (the caller has the code and environment): further forces see a blackhole. */
    public void enter(Node location) {
        if (code == BLACKHOLE) throw NixException.infiniteRecursion(location);
        code = BLACKHOLE;
    }

    public void complete(Object result) {
        data = result;
        code = DONE;
    }

    /** Evaluation failed: pending again, with its code. */
    public void reset(Object pendingCode) {
        code = pendingCode;
    }

    @TruffleBoundary
    public Object forceSlow() {
        Object c = code;
        if (c == DONE) return data;
        enter(null);
        boolean ok = false;
        try {
            Object result = target(c).call(data);
            complete(result);
            ok = true;
            return result;
        } finally {
            if (!ok) reset(c);
        }
    }

    public static Object force(Object v) {
        return v instanceof Thunk t ? t.forceSlow() : v;
    }
}
