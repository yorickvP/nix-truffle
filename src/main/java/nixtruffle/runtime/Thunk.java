package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.Node;

/**
 * A lazy update cell, modelled after thc's {@code Thunk}: a call target plus its captured
 * environment, a small state machine, and the memoized answer.
 *
 * <ul>
 *   <li>PENDING: {@code target}/{@code env} describe the suspended computation.
 *   <li>BLACKHOLE: being evaluated; re-entering it means the value depends on itself, which Nix
 *       reports as "infinite recursion encountered".
 *   <li>DONE: {@code value} holds the WHNF answer. The target and environment are released so the
 *       captured frame can be collected (thc's "evaluated thunk releases its own references").
 * </ul>
 *
 * If evaluation throws, the thunk goes back to PENDING (like CppNix), so {@code builtins.tryEval}
 * and later forces re-run the computation and see the same error again.
 *
 * <p>The fast path lives in {@link nixtruffle.nodes.ForceNode}, which inline-caches the target
 * and calls it through a {@code DirectCallNode} so Graal can inline thunk bodies into force sites.
 * {@link #forceSlow} is for runtime code (builtins, printing, interop).
 */
public final class Thunk {
    private static final int PENDING = 0;
    private static final int BLACKHOLE = 1;
    private static final int DONE = 2;

    private RootCallTarget target;
    /** A {@code MaterializedFrame} for source thunks, an {@code Object[]} for builtin applications. */
    private Object env;
    private Object value;
    private int state;

    public Thunk(RootCallTarget target, Object env) {
        this.target = target;
        this.env = env;
    }

    public boolean isDone() { return state == DONE; }
    public Object getValue() { return value; }
    public RootCallTarget getTarget() { return target; }
    public Object getEnv() { return env; }

    public void enter(Node location) {
        if (state == BLACKHOLE) throw NixException.infiniteRecursion(location);
        state = BLACKHOLE;
    }

    public void complete(Object result) {
        value = result;
        state = DONE;
        target = null;
        env = null;
    }

    public void reset() {
        state = PENDING;
    }

    @TruffleBoundary
    public Object forceSlow() {
        if (state == DONE) return value;
        enter(null);
        boolean ok = false;
        try {
            Object result = target.call(env);
            complete(result);
            ok = true;
            return result;
        } finally {
            if (!ok) reset();
        }
    }

    public static Object force(Object v) {
        return v instanceof Thunk t ? t.forceSlow() : v;
    }
}
