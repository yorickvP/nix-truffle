package nixtruffle.nodes;

import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.source.SourceSection;
import nixtruffle.runtime.NixException;

/**
 * Base expression node. {@link #execute} of a node in a strict position returns a value in weak head
 * normal form; nodes the translator puts in lazy positions ({@link MakeThunkNode},
 * {@link ReadRawVarNode}, constants, lambdas) may return a {@link nixtruffle.runtime.Thunk}.
 *
 * <p>Every lambda body and every thunk body is its own root ({@link NixRootNode}), called with the
 * environment it closes over as {@code arguments[0]} (and a lambda's argument as {@code
 * arguments[1]}). A root's environment ({@link #env}) is an {@code Object[]} of its local
 * variables after the enclosing environment at index 0, or the enclosing environment itself if it
 * has no locals; variables are addressed as (depth, slot) in that chain.
 */
@TypeSystemReference(NixTypes.class)
@NodeInfo(language = "Nix")
public abstract class NixNode extends Node {
    private SourceSection sourceSection;

    public abstract Object execute(VirtualFrame frame);

    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        return NixTypesGen.expectLong(execute(frame));
    }

    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        return NixTypesGen.expectDouble(execute(frame));
    }

    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        return NixTypesGen.expectBoolean(execute(frame));
    }

    public final boolean executeCondition(VirtualFrame frame) {
        try {
            return executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            throw NixException.typeError(e.getResult(), "a Boolean", this);
        }
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }

    public void setSourceSection(SourceSection section) {
        this.sourceSection = section;
    }

    /** The running root's environment. */
    protected static Object[] env(VirtualFrame frame) {
        return (Object[]) frame.getObjectStatic(NixRootNode.ENV_SLOT);
    }

    /** The environment {@code depth} levels out. */
    @ExplodeLoop
    protected static Object[] envAt(VirtualFrame frame, int depth) {
        Object[] env = env(frame);
        for (int i = 0; i < depth; i++) env = (Object[]) env[0];
        return env;
    }
}
