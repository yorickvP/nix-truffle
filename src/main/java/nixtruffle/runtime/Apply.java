package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import nixtruffle.NixLanguage;

import java.util.Arrays;

/** Generic (uncached) function application, used by builtins, interop and cold call sites. */
public final class Apply {
    private Apply() {}

    @TruffleBoundary
    public static Object apply(Object fn, Object arg, Node location) {
        Object f = Thunk.force(fn);
        // Lambdas count towards max-call-depth in their body; primops here, and a functor is a
        // call of the set that calls the functor (as in CppNix).
        if (f instanceof NixLambda lambda) return lambda.target.call(lambda.env, arg);
        if (f instanceof Builtin b) return b.arity == 1 ? primop(b, new Object[] {arg}, location) : partial(b, new Object[] {arg}, location);
        if (f instanceof PartialApp p) {
            Object[] args = Arrays.copyOf(p.args, p.args.length + 1);
            args[p.args.length] = arg;
            return args.length == p.fn.arity ? primop(p.fn, args, location) : partial(p.fn, args, location);
        }
        if (f instanceof NixAttrs attrs) {
            Object functor = attrs.get("__functor");
            if (functor != null) {
                EvalThread ctx = CallDepth.enter(location);
                try {
                    return apply(apply(functor, attrs, location), arg, location);
                } finally {
                    CallDepth.exit(ctx);
                }
            }
        }
        if (Foreign.isForeign(f)) {
            Parallel.mainOnly(location, "a foreign function");
            return Foreign.call(f, new Object[] {arg}, location);
        }
        throw NixException.error("attempt to call something which is not a function but " + Values.typeName(f) + ": " + ValuePrinter.printForError(f), location);
    }

    /**
     * A call from outside the evaluation (the launcher's internals, other languages): a primop
     * called this way is not a level of max-call-depth, as the top level isn't in CppNix.
     */
    static Object applyFromHost(Object fn, Object arg) {
        Object f = Thunk.force(fn);
        if (f instanceof Builtin b && b.arity == 1) return b.impl.apply(new Object[] {arg});
        if (f instanceof PartialApp p && p.args.length + 1 == p.fn.arity) {
            Object[] args = Arrays.copyOf(p.args, p.args.length + 1);
            args[p.args.length] = arg;
            return p.fn.impl.apply(args);
        }
        return apply(f, arg, null);
    }

    private static Object primop(Builtin b, Object[] args, Node location) {
        if (b.mainOnly) Parallel.mainOnly(location, b.name);
        EvalThread ctx = CallDepth.enter(location);
        try {
            return b.impl.apply(args);
        } finally {
            CallDepth.exit(ctx);
        }
    }

    private static PartialApp partial(Builtin b, Object[] args, Node location) {
        CallDepth.check(location);
        return new PartialApp(b, args);
    }

    public static Object apply(Object fn, Object a, Object b) {
        return apply(apply(fn, a, null), b, null);
    }

    /** A lazy application {@code fn args...}, used by builtins such as {@code map} and {@code genList}. */
    public static Thunk lazy(Object fn, Object... args) {
        Object[] env = new Object[args.length + 1];
        env[0] = fn;
        System.arraycopy(args, 0, env, 1, args.length);
        return new Thunk(NixLanguage.get(null).applyThunkTarget(), env);
    }
}
