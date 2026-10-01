package nixtruffle.nodes;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import nixtruffle.NixLanguage;
import nixtruffle.runtime.Thunk;

/**
 * Suspends an expression: captures the current frame together with the expression's own root.
 * Until the root is built (when the first of these thunks is forced), thunks run a {@link
 * LazyThunkRootNode} that builds it.
 */
public final class MakeThunkNode extends NixNode {
    private final LazyCode code;

    public MakeThunkNode(LazyCode code) {
        this.code = code;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Not a compilation constant: building the root elsewhere shouldn't invalidate this code.
        RootCallTarget t = code.built();
        if (t == null) return new Thunk(NixLanguage.get(this).lazyThunkTarget(), new LazyCode.Env(code, env(frame)));
        return new Thunk(t, env(frame));
    }
}
