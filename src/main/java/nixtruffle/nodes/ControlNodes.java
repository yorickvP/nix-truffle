package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import nixtruffle.runtime.NixException;

/** if/assert/let/with and the boolean operators. */
public final class ControlNodes {
    private ControlNodes() {}

    public static final class If extends NixNode {
        @Child private NixNode cond;
        @Child private NixNode then;
        @Child private NixNode otherwise;
        private final CountingConditionProfile profile = CountingConditionProfile.create();

        public If(NixNode cond, NixNode then, NixNode otherwise) {
            this.cond = cond;
            this.then = then;
            this.otherwise = otherwise;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return profile.profile(cond.executeCondition(frame)) ? then.execute(frame) : otherwise.execute(frame);
        }
    }

    public static final class Assert extends NixNode {
        @Child private NixNode cond;
        @Child private NixNode body;

        public Assert(NixNode cond, NixNode body) {
            this.cond = cond;
            this.body = body;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (!cond.executeCondition(frame)) throw new NixException.Catchable("assertion failed", this);
            return body.execute(frame);
        }
    }

    /** Stores a (lazy) value into a frame slot. */
    public static final class WriteSlot extends Node {
        private final int slot;
        @Child private NixNode value;

        public WriteSlot(int slot, NixNode value) {
            this.slot = slot;
            this.value = value;
        }

        public void execute(VirtualFrame frame) {
            frame.setObject(slot, value.execute(frame));
        }
    }

    /**
     * {@code let} and {@code rec}: all bindings are written before any of them can be forced, and
     * bindings that refer to each other got thunks from the translator, so the order is irrelevant.
     */
    public static final class Let extends NixNode {
        @Children private final WriteSlot[] writes;
        @Child private NixNode body;

        public Let(WriteSlot[] writes, NixNode body) {
            this.writes = writes;
            this.body = body;
        }

        @Override
        @ExplodeLoop
        public Object execute(VirtualFrame frame) {
            for (WriteSlot w : writes) w.execute(frame);
            return body.execute(frame);
        }
    }

    /** {@code with e; body}: stores {@code e} (lazily) in a slot that {@link WithLookupNode} consults. */
    public static final class With extends NixNode {
        private final int slot;
        @Child private NixNode env;
        @Child private NixNode body;

        public With(int slot, NixNode env, NixNode body) {
            this.slot = slot;
            this.env = env;
            this.body = body;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            frame.setObject(slot, env.execute(frame));
            return body.execute(frame);
        }
    }

    /** Throws a catchable error when evaluated (e.g. a search path that could not be found). */
    public static final class Throw extends NixNode {
        private final String message;

        public Throw(String message) { this.message = message; }

        @Override
        public Object execute(VirtualFrame frame) {
            throw new NixException.Catchable(message, this);
        }
    }

    public static final class Not extends NixNode {
        @Child private NixNode operand;

        public Not(NixNode operand) { this.operand = operand; }

        @Override
        public boolean executeBoolean(VirtualFrame frame) { return !operand.executeCondition(frame); }

        @Override
        public Object execute(VirtualFrame frame) { return executeBoolean(frame); }
    }

    public static final class And extends NixNode {
        @Child private NixNode left;
        @Child private NixNode right;

        public And(NixNode left, NixNode right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) { return left.executeCondition(frame) && right.executeCondition(frame); }

        @Override
        public Object execute(VirtualFrame frame) { return executeBoolean(frame); }
    }

    public static final class Or extends NixNode {
        @Child private NixNode left;
        @Child private NixNode right;

        public Or(NixNode left, NixNode right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) { return left.executeCondition(frame) || right.executeCondition(frame); }

        @Override
        public Object execute(VirtualFrame frame) { return executeBoolean(frame); }
    }

    public static final class Impl extends NixNode {
        @Child private NixNode left;
        @Child private NixNode right;

        public Impl(NixNode left, NixNode right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) { return !left.executeCondition(frame) || right.executeCondition(frame); }

        @Override
        public Object execute(VirtualFrame frame) { return executeBoolean(frame); }
    }
}
