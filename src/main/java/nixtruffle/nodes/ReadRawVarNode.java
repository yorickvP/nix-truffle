package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

/** Variable read in a lazy position: shares the binding's thunk instead of wrapping it in a new one. */
public final class ReadRawVarNode extends NixNode {
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private int depth;
    private final int slot;

    public ReadRawVarNode(int depth, int slot) {
        this.depth = depth;
        this.slot = slot;
    }

    /** See {@link ReadVarNode#shallower}. */
    public void shallower() {
        depth--;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return envAt(frame, depth)[slot];
    }
}
