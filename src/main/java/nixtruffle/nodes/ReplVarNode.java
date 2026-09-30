package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;

/** A REPL-scope variable: an attribute of the attrset the REPL input's root was called with. */
public final class ReplVarNode extends NixNode {
    private final int depth;
    private final String name;
    @Child private SelectStepNode step = SelectStepNode.create();

    public ReplVarNode(int depth, String name) {
        this.depth = depth;
        this.name = name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        NixAttrs scope = (NixAttrs) frameAt(frame, depth).getArguments()[0];
        Object value = step.execute(scope, name);
        if (value == SelectStepNode.MISSING) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw NixException.error("undefined variable '" + name + "'", this);
        }
        return value;
    }
}
