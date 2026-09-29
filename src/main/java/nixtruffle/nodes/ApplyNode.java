package nixtruffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import nixtruffle.runtime.Foreign;

import java.util.Arrays;

/**
 * {@code f a b c}: arguments are lazy nodes, applied one at a time with one dispatch cache per
 * position. When the function turns out to be a foreign executable, all remaining arguments are
 * passed in a single foreign call, so {@code (polyglotEval "js" "(x, y) => x + y") 1 2} works.
 */
public final class ApplyNode extends NixNode {
    @Child private NixNode fn;
    @Children private final NixNode[] args;
    @Children private final DispatchNode[] dispatch;

    public ApplyNode(NixNode fn, NixNode[] args) {
        this.fn = fn;
        this.args = args;
        this.dispatch = new DispatchNode[args.length];
        for (int i = 0; i < args.length; i++) dispatch[i] = DispatchNode.create();
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object f = fn.execute(frame);
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) values[i] = args[i].execute(frame);
        for (int i = 0; i < args.length; i++) {
            if (Foreign.isForeign(f)) return foreignCall(f, values, i);
            f = dispatch[i].execute(f, values[i]);
        }
        return f;
    }

    @TruffleBoundary
    private Object foreignCall(Object f, Object[] values, int from) {
        return Foreign.call(f, Arrays.copyOfRange(values, from, values.length), this);
    }
}
