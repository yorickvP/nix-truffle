package nixtruffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import nixtruffle.runtime.Values;

/** {@code "a ${b} c"}. */
public final class InterpolationNode extends NixNode {
    @Children private final NixNode[] parts;

    public InterpolationNode(NixNode[] parts) { this.parts = parts; }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] values = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) values[i] = parts[i].execute(frame);
        return concat(values);
    }

    @TruffleBoundary
    private String concat(Object[] values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) sb.append(Values.coerceToString(v, false, this));
        return sb.toString();
    }
}
