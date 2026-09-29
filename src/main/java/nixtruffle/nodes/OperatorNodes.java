package nixtruffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import nixtruffle.runtime.Arith;
import nixtruffle.runtime.Foreign;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.Values;

/** Binary operators, specialized on unboxed ints/floats with generic fallbacks. */
public final class OperatorNodes {
    private OperatorNodes() {}

    @NodeChild("left")
    @NodeChild("right")
    public abstract static class Binary extends NixNode {}

    public abstract static class Add extends Binary {
        @Specialization
        protected long doLong(long a, long b) {
            try {
                return Math.addExact(a, b);
            } catch (ArithmeticException e) {
                throw NixException.overflow("adding", a, b, this);
            }
        }

        @Specialization
        protected double doDouble(double a, double b) { return a + b; }

        @Specialization
        @TruffleBoundary
        protected String doString(String a, String b) { return a.concat(b); }

        @Fallback
        protected Object doOther(Object a, Object b) { return Arith.add(a, b, this); }
    }

    public abstract static class Sub extends Binary {
        @Specialization
        protected long doLong(long a, long b) {
            try {
                return Math.subtractExact(a, b);
            } catch (ArithmeticException e) {
                throw NixException.overflow("subtracting", a, b, this);
            }
        }

        @Specialization
        protected double doDouble(double a, double b) { return a - b; }

        @Fallback
        protected Object doOther(Object a, Object b) { return Arith.sub(a, b, this); }
    }

    public abstract static class Mul extends Binary {
        @Specialization
        protected long doLong(long a, long b) {
            try {
                return Math.multiplyExact(a, b);
            } catch (ArithmeticException e) {
                throw NixException.overflow("multiplying", a, b, this);
            }
        }

        @Specialization
        protected double doDouble(double a, double b) { return a * b; }

        @Fallback
        protected Object doOther(Object a, Object b) { return Arith.mul(a, b, this); }
    }

    public abstract static class Div extends Binary {
        @Specialization
        protected long doLong(long a, long b) { return Arith.divLong(a, b, this); }

        @Specialization
        protected double doDouble(double a, double b) {
            if (b == 0) throw NixException.error("division by zero", this);
            return a / b;
        }

        @Fallback
        protected Object doOther(Object a, Object b) { return Arith.div(a, b, this); }
    }

    public abstract static class LessThan extends Binary {
        @Specialization
        protected boolean doLong(long a, long b) { return a < b; }

        @Specialization
        protected boolean doDouble(double a, double b) { return a < b; }

        @Specialization
        @TruffleBoundary
        protected boolean doString(String a, String b) { return a.compareTo(b) < 0; }

        @Fallback
        protected boolean doOther(Object a, Object b) { return Values.lessThan(a, b, this); }
    }

    public abstract static class Equal extends Binary {
        @Specialization
        protected boolean doLong(long a, long b) { return a == b; }

        @Specialization
        protected boolean doDouble(double a, double b) { return a == b; }

        @Specialization
        protected boolean doBoolean(boolean a, boolean b) { return a == b; }

        @Specialization
        @TruffleBoundary
        protected boolean doString(String a, String b) { return a.equals(b); }

        @Fallback
        protected boolean doOther(Object a, Object b) { return Values.equalTop(a, b); }
    }

    /** {@code ++} */
    public static final class Concat extends NixNode {
        @Child private NixNode left;
        @Child private NixNode right;

        public Concat(NixNode left, NixNode right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object a = left.execute(frame);
            Object b = right.execute(frame);
            return NixList.concat(items(a), items(b));
        }

        private Object[] items(Object v) {
            if (v instanceof NixList l) return l.items;
            Object[] foreign = Foreign.isForeign(v) ? Foreign.asArray(v) : null;
            if (foreign != null) return foreign;
            throw NixException.typeError(v, "a list", this);
        }
    }

    /** {@code //} */
    public static final class Update extends NixNode {
        @Child private NixNode left;
        @Child private NixNode right;

        public Update(NixNode left, NixNode right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object a = left.execute(frame);
            Object b = right.execute(frame);
            if (!(a instanceof NixAttrs x)) throw NixException.typeError(a, "a set", this);
            if (!(b instanceof NixAttrs y)) throw NixException.typeError(b, "a set", this);
            return x.update(y);
        }
    }
}
