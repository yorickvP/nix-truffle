package nixtruffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixNull;

import java.util.TreeMap;

/**
 * Attrset construction. Static keys are sorted at translation time and the same key array is shared
 * by every set this node creates (see {@link SelectStepNode}). Values are lazy nodes.
 */
public final class AttrsNode extends NixNode {
    @CompilationFinal(dimensions = 1) private final String[] keys;
    @Children private final NixNode[] values;
    @Children private final NixNode[] dynamicKeys;
    @Children private final NixNode[] dynamicValues;

    public AttrsNode(String[] keys, NixNode[] values, NixNode[] dynamicKeys, NixNode[] dynamicValues) {
        this.keys = keys;
        this.values = values;
        this.dynamicKeys = dynamicKeys;
        this.dynamicValues = dynamicValues;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] vals = new Object[values.length];
        for (int i = 0; i < values.length; i++) vals[i] = values[i].execute(frame);
        if (dynamicKeys.length == 0) return new NixAttrs(keys, vals);
        Object[] dk = new Object[dynamicKeys.length];
        Object[] dv = new Object[dynamicKeys.length];
        for (int i = 0; i < dynamicKeys.length; i++) {
            dk[i] = dynamicKeys[i].execute(frame);
            dv[i] = dynamicValues[i].execute(frame);
        }
        return withDynamic(vals, dk, dv);
    }

    @TruffleBoundary
    private NixAttrs withDynamic(Object[] vals, Object[] dk, Object[] dv) {
        TreeMap<String, Object> map = new TreeMap<>();
        for (int i = 0; i < keys.length; i++) map.put(keys[i], vals[i]);
        for (int i = 0; i < dk.length; i++) {
            if (dk[i] instanceof NixNull) continue;
            if (!nixtruffle.runtime.NixString.is(dk[i])) throw NixException.typeError(dk[i], "a string", this);
            if (dk[i] instanceof nixtruffle.runtime.NixString ctx) {
                throw NixException.error("the string '" + ctx.value + "' is not allowed to refer to a store path (such as '" + ctx.context[0] + "')", this);
            }
            String name = nixtruffle.runtime.NixString.value(dk[i]);
            if (map.put(name, dv[i]) != null) throw NixException.error("dynamic attribute '" + name + "' already defined", this);
        }
        return NixAttrs.fromMap(map);
    }
}
