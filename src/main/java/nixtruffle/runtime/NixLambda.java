package nixtruffle.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.MaterializedFrame;

/** A closure: the lambda's call target plus the frame it was created in. Called with {@code (env, arg)}. */
public final class NixLambda extends NixFunction {
    public final RootCallTarget target;
    public final MaterializedFrame env;
    public final Info info;

    /** Static facts about the lambda, for error messages and {@code builtins.functionArgs}. */
    public record Info(String name, String[] formals, boolean[] hasDefault, boolean hasFormals, boolean ellipsis) {}

    public NixLambda(RootCallTarget target, MaterializedFrame env, Info info) {
        this.target = target;
        this.env = env;
        this.info = info;
    }
}
