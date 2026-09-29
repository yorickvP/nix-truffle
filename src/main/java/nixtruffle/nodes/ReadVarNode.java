package nixtruffle.nodes;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Strict variable read. After forcing, the answer replaces the thunk in the binding's slot (thc's
 * "writeForced"), so later reads of the same binding skip the thunk entirely. Bindings are
 * immutable, so this is invisible to the program.
 */
public final class ReadVarNode extends NixNode {
    private final int depth;
    private final int slot;
    @Child private ForceNode force = ForceNode.create();

    public ReadVarNode(int depth, int slot) {
        this.depth = depth;
        this.slot = slot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Frame f = frameAt(frame, depth);
        Object raw = f.getObject(slot);
        Object value = force.execute(raw);
        if (value != raw) f.setObject(slot, value);
        return value;
    }
}
