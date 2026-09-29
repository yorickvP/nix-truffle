package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import nixtruffle.runtime.Foreign;
import nixtruffle.runtime.NixAttrs;

/** {@code e ? a.b.c}: forces the intermediate sets but not the final attribute. */
public final class HasAttrNode extends NixNode {
    @Child private NixNode target;
    @Children private final AttrKeyNode[] keys;
    @Children private final SelectStepNode[] steps;

    public HasAttrNode(NixNode target, AttrKeyNode[] keys) {
        this.target = target;
        this.keys = keys;
        this.steps = new SelectStepNode[keys.length - 1];
        for (int i = 0; i < steps.length; i++) steps[i] = SelectStepNode.create();
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object current = target.execute(frame);
        for (int i = 0; i < steps.length; i++) {
            current = steps[i].execute(current, keys[i].execute(frame));
            if (current == SelectStepNode.MISSING || current == SelectStepNode.NOT_ATTRS) return false;
        }
        String last = keys[keys.length - 1].execute(frame);
        if (current instanceof NixAttrs attrs) return attrs.indexOf(last) >= 0;
        return Foreign.isForeign(current) && Foreign.select(current, last) != null;
    }
}
