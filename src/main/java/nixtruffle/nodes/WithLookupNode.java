package nixtruffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import nixtruffle.runtime.NixException;

/** A variable that is not lexically bound: searched in the enclosing {@code with} scopes, innermost first. */
public final class WithLookupNode extends NixNode {
    private final String name;
    @CompilationFinal(dimensions = 1) private final int[] depths;
    @CompilationFinal(dimensions = 1) private final int[] slots;
    @Children private final ForceNode[] envForces;
    @Children private final SelectStepNode[] steps;

    public WithLookupNode(String name, int[] depths, int[] slots) {
        this.name = name;
        this.depths = depths;
        this.slots = slots;
        this.envForces = new ForceNode[depths.length];
        this.steps = new SelectStepNode[depths.length];
        for (int i = 0; i < depths.length; i++) {
            envForces[i] = ForceNode.create();
            steps[i] = SelectStepNode.create();
        }
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        for (int i = 0; i < depths.length; i++) {
            Frame f = frameAt(frame, depths[i]);
            Object raw = f.getObject(slots[i]);
            Object env = envForces[i].execute(raw);
            if (env != raw) f.setObject(slots[i], env);
            Object value = steps[i].execute(env, name);
            if (value == SelectStepNode.NOT_ATTRS) throw NixException.typeError(env, "a set", this);
            if (value != SelectStepNode.MISSING) return value;
        }
        com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
        throw NixException.error("undefined variable '" + name + "'", this);
    }
}
