package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import nixtruffle.NixContext;
import nixtruffle.runtime.CallDepth;

/**
 * An operator that is a primop call in CppNix ({@code - * / < > <= >=}, unary {@code -}): its
 * operands are evaluated one {@code max-call-depth} level deeper.
 */
public final class CallLevelNode extends NixNode {
    @Child private NixNode body;

    public CallLevelNode(NixNode body) {
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        NixContext ctx = CallDepth.enter(this);
        try {
            return body.execute(frame);
        } finally {
            CallDepth.exit(ctx);
        }
    }
}
