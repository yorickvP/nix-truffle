package nixtruffle.nodes;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import nixtruffle.runtime.Thunk;

/** Suspends an expression: captures the current frame together with the expression's own root. */
public final class MakeThunkNode extends NixNode {
    private final RootCallTarget target;

    public MakeThunkNode(RootCallTarget target) {
        this.target = target;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return new Thunk(target, frame.materialize());
    }
}
