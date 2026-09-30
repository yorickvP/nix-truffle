package nixtruffle.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixLambda;

/** Closure creation and the prologues that bind a lambda's argument. */
public final class FunctionNodes {
    private FunctionNodes() {}

    public static final class Lambda extends NixNode {
        private final RootCallTarget target;
        private final NixLambda.Info info;

        public Lambda(RootCallTarget target, NixLambda.Info info) {
            this.target = target;
            this.info = info;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return new NixLambda(target, frame.materialize(), info);
        }
    }

    /** Runs the argument-binding prologue, then the body. */
    public static final class Body extends NixNode {
        @Child private NixNode prologue;
        @Child private NixNode body;

        public Body(NixNode prologue, NixNode body) {
            this.prologue = prologue;
            this.body = body;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            prologue.execute(frame);
            return body.execute(frame);
        }
    }

    /** {@code x: body}: the (lazy) argument goes straight into the slot. */
    public static final class BindArg extends NixNode {
        private final int slot;

        public BindArg(int slot) { this.slot = slot; }

        @Override
        public Object execute(VirtualFrame frame) {
            frame.setObject(slot, frame.getArguments()[1]);
            return null;
        }
    }

    /**
     * {@code { a, b ? d, ... }@args: body}. Forces the argument to a set and binds each formal to
     * the set's (still lazy) value or its default. The formal -> index mapping is cached for the
     * last seen key array, which covers call sites that pass attrset literals.
     */
    public static final class BindFormals extends NixNode {
        private final String functionName;
        @CompilationFinal(dimensions = 1) private final String[] names;
        @CompilationFinal(dimensions = 1) private final int[] slots;
        @CompilationFinal(dimensions = 1) private final int[] defaultIndex;
        @Children private final NixNode[] defaults;
        private final boolean ellipsis;
        private final int argSlot;
        @Child private ForceNode force = ForceNode.create();

        /** The formals' indices in an argument with these keys (one object: contexts may run this concurrently). */
        private record Cached(String[] keys, @CompilationFinal(dimensions = 1) int[] indices) {}

        @CompilationFinal private Cached cached;
        @CompilationFinal private boolean generic;

        public BindFormals(String functionName, String[] names, int[] slots, int[] defaultIndex, NixNode[] defaults,
                           boolean ellipsis, int argSlot) {
            this.functionName = functionName;
            this.names = names;
            this.slots = slots;
            this.defaultIndex = defaultIndex;
            this.defaults = defaults;
            this.ellipsis = ellipsis;
            this.argSlot = argSlot;
        }

        @Override
        @ExplodeLoop
        public Object execute(VirtualFrame frame) {
            Object arg = force.execute(frame.getArguments()[1]);
            if (!(arg instanceof NixAttrs attrs)) throw NixException.typeError(arg, "a set", this);
            if (argSlot >= 0) frame.setObject(argSlot, arg);
            int[] indices = indicesFor(attrs);
            int matched = 0;
            for (int i = 0; i < names.length; i++) {
                int index = indices[i];
                if (index >= 0) {
                    frame.setObject(slots[i], attrs.values[index]);
                    matched++;
                } else if (defaultIndex[i] >= 0) {
                    frame.setObject(slots[i], defaults[defaultIndex[i]].execute(frame));
                } else {
                    throw NixException.error("function '" + functionName + "' called without required argument '" + names[i] + "'", this);
                }
            }
            if (!ellipsis && matched != attrs.size()) throw unexpected(attrs);
            return null;
        }

        private int[] indicesFor(NixAttrs attrs) {
            Cached c = cached;
            if (c != null && attrs.keys == c.keys) return c.indices;
            if (!generic) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                if (c == null) {
                    c = new Cached(attrs.keys, lookup(names, attrs.keys));
                    cached = c;
                    return c.indices;
                }
                generic = true;
            }
            return lookup(names, attrs.keys);
        }

        @TruffleBoundary
        private static int[] lookup(String[] names, String[] keys) {
            int[] out = new int[names.length];
            for (int i = 0; i < names.length; i++) out[i] = NixAttrs.indexOf(keys, names[i]);
            return out;
        }

        @TruffleBoundary
        private NixException unexpected(NixAttrs attrs) {
            for (String key : attrs.keys) {
                if (java.util.Arrays.asList(names).indexOf(key) < 0) {
                    return NixException.error("function '" + functionName + "' called with unexpected argument '" + key + "'", this);
                }
            }
            return NixException.error("function '" + functionName + "' called with unexpected argument", this);
        }
    }
}
