package nixtruffle.nodes;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import nixtruffle.NixLanguage;

/**
 * The body of thunks whose code had not been built when they were made ({@link LazyCode}): builds
 * it, and runs it on the frame the thunk closes over. Thunks made later run the built code directly.
 */
public final class LazyThunkRootNode extends RootNode {
    @Child private IndirectCallNode call = IndirectCallNode.create();

    public LazyThunkRootNode(NixLanguage language) {
        super(language, new FrameDescriptor());
    }

    @Override
    public Object execute(VirtualFrame frame) {
        LazyCode.Env env = (LazyCode.Env) frame.getArguments()[0];
        return call.call(env.code().target(), env.frame());
    }

    @Override
    public String getName() {
        return "<thunk>";
    }
}
