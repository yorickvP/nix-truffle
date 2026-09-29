package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class LiteralNodes {
    private LiteralNodes() {}

    public static final class LongLiteral extends NixNode {
        private final long value;

        public LongLiteral(long value) { this.value = value; }

        @Override
        public long executeLong(VirtualFrame frame) { return value; }

        @Override
        public Object execute(VirtualFrame frame) { return value; }
    }

    public static final class DoubleLiteral extends NixNode {
        private final double value;

        public DoubleLiteral(double value) { this.value = value; }

        @Override
        public double executeDouble(VirtualFrame frame) { return value; }

        @Override
        public Object execute(VirtualFrame frame) { return value; }
    }

    /** Strings, paths, builtins and other values known at translation time. */
    public static final class Constant extends NixNode {
        private final Object value;

        public Constant(Object value) { this.value = value; }

        @Override
        public Object execute(VirtualFrame frame) { return value; }
    }
}
