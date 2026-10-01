package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import nixtruffle.runtime.Thunk;

/**
 * Suspends an expression: a thunk of the expression's code (built when the first of its thunks is
 * forced, see {@link LazyCode}) and the current environment.
 */
public final class MakeThunkNode extends NixNode {
    private final LazyCode code;

    public MakeThunkNode(LazyCode code) {
        this.code = code;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return new Thunk(code, env(frame));
    }
}
