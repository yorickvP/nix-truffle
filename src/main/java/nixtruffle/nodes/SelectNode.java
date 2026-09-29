package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import nixtruffle.runtime.NixException;

/** {@code e.a.b.c} and {@code e.a.b or fallback}. */
public final class SelectNode extends NixNode {
    @Child private NixNode target;
    @Children private final AttrKeyNode[] keys;
    @Children private final SelectStepNode[] steps;
    @Child private NixNode fallback;

    public SelectNode(NixNode target, AttrKeyNode[] keys, NixNode fallback) {
        this.target = target;
        this.keys = keys;
        this.fallback = fallback;
        this.steps = new SelectStepNode[keys.length];
        for (int i = 0; i < keys.length; i++) steps[i] = SelectStepNode.create();
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object current = target.execute(frame);
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i].execute(frame);
            Object next = steps[i].execute(current, key);
            if (next == SelectStepNode.MISSING || next == SelectStepNode.NOT_ATTRS) {
                if (fallback != null) return fallback.execute(frame);
                if (next == SelectStepNode.NOT_ATTRS) throw NixException.typeError(current, "a set", this);
                throw NixException.error("attribute '" + key + "' missing", this);
            }
            current = next;
        }
        return current;
    }
}
