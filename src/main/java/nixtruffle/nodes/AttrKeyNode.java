package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import nixtruffle.runtime.NixException;

/** One component of an attribute path: a constant name or a {@code ${...}} expression. */
public abstract class AttrKeyNode extends Node {
    public abstract String execute(VirtualFrame frame);

    public static final class Static extends AttrKeyNode {
        private final String name;

        public Static(String name) { this.name = name; }

        @Override
        public String execute(VirtualFrame frame) { return name; }
    }

    public static final class Dynamic extends AttrKeyNode {
        @Child private NixNode expr;

        public Dynamic(NixNode expr) { this.expr = expr; }

        @Override
        public String execute(VirtualFrame frame) {
            Object v = expr.execute(frame);
            if (v instanceof String s) return s;
            // Attribute names can't have context (CppNix's getName uses forceStringNoCtx).
            return nixtruffle.runtime.Values.stringNoCtx(v);
        }
    }
}
