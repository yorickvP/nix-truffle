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
        if (f instanceof NixLambda lambda) return lambda.target.call(lambda.env, arg);
        if (f instanceof Builtin b) return b.arity == 1 ? b.impl.apply(new Object[] {arg}) : new PartialApp(b, new Object[] {arg});
        if (f instanceof PartialApp p) {
            Object[] args = Arrays.copyOf(p.args, p.args.length + 1);
            args[p.args.length] = arg;
            return args.length == p.fn.arity ? p.fn.impl.apply(args) : new PartialApp(p.fn, args);
        }
        if (f instanceof NixAttrs attrs) {
            Object functor = attrs.get("__functor");
            if (functor != null) return apply(apply(functor, attrs, location), arg, location);
        }
        if (Foreign.isForeign(f)) return Foreign.call(f, new Object[] {arg}, location);
        throw NixException.error("attempt to call something which is not a function but " + Values.typeName(f) + ": " + ValuePrinter.printForError(f), location);
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
