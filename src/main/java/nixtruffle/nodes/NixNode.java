package nixtruffle.nodes;

import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.Frame;
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
 * <p>Frame layout: {@code arguments[0]} is the lexically enclosing frame (a MaterializedFrame, or
 * absent for a file's top level), {@code arguments[1]} a lambda's argument. Every lambda body and
 * every thunk body is its own root; variables are addressed as (depth, slot).
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

    @ExplodeLoop
    protected static Frame frameAt(VirtualFrame frame, int depth) {
        Frame f = frame;
        for (int i = 0; i < depth; i++) f = (Frame) f.getArguments()[0];
        return f;
    }
}
