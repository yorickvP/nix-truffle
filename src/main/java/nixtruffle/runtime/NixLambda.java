package nixtruffle.runtime;

import com.oracle.truffle.api.RootCallTarget;

/** A closure: the lambda's call target plus the environment it was created in. Called with {@code (env, arg)}. */
public final class NixLambda extends NixFunction {
    public final RootCallTarget target;
    public final Object[] env;
    public final Info info;

    /** Static facts about the lambda, for error messages and {@code builtins.functionArgs}. */
    /** The name of lambdas that aren't bound to a name (for error messages). */
    public static final String ANONYMOUS = "anonymous lambda";

    /** {@code formalPositions}: {@link Pos}es (or nulls) of the formals, for {@code functionArgs}. */
    public record Info(String name, String argName, String[] formals, boolean[] hasDefault, Object[] formalPositions, boolean hasFormals,
            boolean ellipsis) {}

    public NixLambda(RootCallTarget target, Object[] env, Info info) {
        this.target = target;
        this.env = env;
        this.info = info;
    }
}
