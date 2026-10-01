package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Strict variable read. After forcing, the answer replaces the thunk in the binding's slot (thc's
 * "writeForced"), so later reads of the same binding skip the thunk entirely. Bindings are
 * immutable, so this is invisible to the program.
 */
public final class ReadVarNode extends NixNode {
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private int depth;
    private final int slot;
    @Child private ForceNode force = ForceNode.create();

    public ReadVarNode(int depth, int slot) {
        this.depth = depth;
        this.slot = slot;
    }

    /** The translator found the function this is in has no environment of its own. */
    public void shallower() {
        depth--;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] env = envAt(frame, depth);
        Object raw = env[slot];
        Object value = force.execute(raw);
        if (value != raw) env[slot] = value;
        return value;
    }
}
