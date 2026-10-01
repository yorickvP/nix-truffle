package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;

/** A REPL-scope variable: an attribute of the attrset the REPL input's root was called with. */
public final class ReplVarNode extends NixNode {
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private int depth;
    private final String name;
    @Child private SelectStepNode step = SelectStepNode.create();

    public ReplVarNode(int depth, String name) {
        this.depth = depth;
        this.name = name;
    }

    /** See {@link ReadVarNode#shallower}. */
    public void shallower() {
        depth--;
    }

    /** The REPL scope is what the top level's environment encloses. */
    @Override
    public Object execute(VirtualFrame frame) {
        NixAttrs scope = (NixAttrs) envAt(frame, depth)[0];
        Object value = step.execute(scope, name);
        if (value == SelectStepNode.MISSING) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw NixException.error("undefined variable '" + name + "'", this);
        }
        return value;
    }
}
