package nixtruffle.nodes;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import nixtruffle.runtime.Thunk;

/**
 * Evaluates a value to weak head normal form. This is where laziness is paid for, so it follows
 * thc's Force/DispatchThunkTarget design:
 *
 * <ul>
 *   <li>Sites that have only ever seen evaluated values keep just the {@code doValue}/{@code doDone}
 *       specializations: a type check or a state check, no call.
 *   <li>Unevaluated thunks are dispatched through an inline cache on the thunk's call target, so a
 *       monomorphic force site calls a {@link DirectCallNode} that Graal can inline: the thunk body
 *       is compiled into its consumer.
 *   <li>The thunk is blackholed while it runs, and memoizes the answer (or resets on error).
 * </ul>
 */
public abstract class ForceNode extends Node {
    public abstract Object execute(Object value);

    public static ForceNode create() {
        return ForceNodeGen.create();
    }

    @Specialization(guards = "!isThunk(value)")
    protected static Object doValue(Object value) {
        return value;
    }

    @Specialization(guards = "thunk.isDone()")
    protected static Object doDone(Thunk thunk) {
        return thunk.getValue();
    }

    @Specialization(guards = {"!thunk.isDone()", "thunk.getTarget() == cachedTarget"}, limit = "3")
    protected Object doDirect(Thunk thunk,
                              @Cached("thunk.getTarget()") RootCallTarget cachedTarget,
                              @Cached("create(cachedTarget)") DirectCallNode call) {
        thunk.enter(this);
        boolean ok = false;
        try {
            Object result = call.call(thunk.getEnv());
            thunk.complete(result);
            ok = true;
            return result;
        } finally {
            if (!ok) thunk.reset();
        }
    }

    @Specialization(guards = "!thunk.isDone()", replaces = "doDirect")
    protected Object doIndirect(Thunk thunk, @Cached IndirectCallNode call) {
        thunk.enter(this);
        boolean ok = false;
        try {
            Object result = call.call(thunk.getTarget(), thunk.getEnv());
            thunk.complete(result);
            ok = true;
            return result;
        } finally {
            if (!ok) thunk.reset();
        }
    }

    protected static boolean isThunk(Object value) {
        return value instanceof Thunk;
    }
}
