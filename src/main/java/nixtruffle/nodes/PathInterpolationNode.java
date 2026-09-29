package nixtruffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixPath;
import nixtruffle.runtime.Values;

import java.util.Set;
import java.util.TreeSet;

/** {@code ./patches/${name}.patch}: path concatenation (no store copies, no string context allowed). */
public final class PathInterpolationNode extends NixNode {
    private final String first;
    @Children private final NixNode[] parts;

    public PathInterpolationNode(String first, NixNode[] parts) {
        this.first = first;
        this.parts = parts;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] values = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) values[i] = parts[i].execute(frame);
        return concat(values);
    }

    @TruffleBoundary
    private NixPath concat(Object[] values) {
        StringBuilder sb = new StringBuilder(first);
        Set<String> context = new TreeSet<>();
        for (Object v : values) sb.append(Values.coerce(v, false, false, context, this));
        if (!context.isEmpty()) throw NixException.error("a string that refers to a store path cannot be appended to a path", this);
        return new NixPath(NixPath.canonicalize(sb.toString()));
    }
}
