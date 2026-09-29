package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import nixtruffle.runtime.NixList;

public final class ListNode extends NixNode {
    @Children private final NixNode[] items;

    public ListNode(NixNode[] items) { this.items = items; }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] values = new Object[items.length];
        for (int i = 0; i < items.length; i++) values[i] = items[i].execute(frame);
        return new NixList(values);
    }
}
