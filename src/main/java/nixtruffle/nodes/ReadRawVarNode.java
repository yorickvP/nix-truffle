package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

/** Variable read in a lazy position: shares the binding's thunk instead of wrapping it in a new one. */
public final class ReadRawVarNode extends NixNode {
    private final int depth;
    private final int slot;

    public ReadRawVarNode(int depth, int slot) {
        this.depth = depth;
        this.slot = slot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return frameAt(frame, depth).getObject(slot);
    }
}
