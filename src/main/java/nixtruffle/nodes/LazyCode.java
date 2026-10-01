package nixtruffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;

/**
 * A thunk or lambda body whose nodes are built when it is first needed. Most of the code an
 * evaluation reads never runs (in a NixOS evaluation, four in five thunk bodies are never forced),
 * so the translator only checks it for errors, and defers the rest (see {@code Translator}).
 */
public abstract class LazyCode {
    private volatile RootCallTarget target;

    /** Builds the body's root; called once. */
    protected abstract RootCallTarget build();

    /** The root, if it has been built; else null. */
    public final RootCallTarget built() {
        return target;
    }

    /** The root, built now if it hasn't been. */
    @TruffleBoundary
    public final RootCallTarget target() {
        RootCallTarget t = target;
        if (t == null) {
            synchronized (this) {
                t = target;
                if (t == null) target = t = build();
            }
        }
        return t;
    }
}
