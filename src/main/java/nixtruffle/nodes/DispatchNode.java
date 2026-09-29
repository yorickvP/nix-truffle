package nixtruffle.nodes;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import nixtruffle.runtime.Apply;
import nixtruffle.runtime.NixLambda;

/** Applies one argument: inline cache on the lambda's call target, generic path for builtins/functors. */
public abstract class DispatchNode extends Node {
    public abstract Object execute(Object fn, Object arg);

    public static DispatchNode create() {
        return DispatchNodeGen.create();
    }

    @Specialization(guards = "fn.target == cachedTarget", limit = "3")
    protected static Object doDirect(NixLambda fn, Object arg,
                                     @Cached("fn.target") RootCallTarget cachedTarget,
                                     @Cached("create(cachedTarget)") DirectCallNode call) {
        return call.call(fn.env, arg);
    }

    @Specialization(replaces = "doDirect")
    protected static Object doIndirect(NixLambda fn, Object arg, @Cached IndirectCallNode call) {
        return call.call(fn.target, fn.env, arg);
    }

    @Fallback
    protected Object doOther(Object fn, Object arg) {
        return Apply.apply(fn, arg, this);
    }
}
