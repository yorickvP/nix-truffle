package nixtruffle.nodes;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import nixtruffle.NixLanguage;
import nixtruffle.runtime.Apply;

/** Body of thunks created by builtins ({@code map f xs} elements etc.): env is {@code [f, arg1, ...]}. */
public final class ApplyThunkRootNode extends RootNode {
    public ApplyThunkRootNode(NixLanguage language) {
        super(language, new FrameDescriptor());
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] env = (Object[]) frame.getArguments()[0];
        Object f = env[0];
        for (int i = 1; i < env.length; i++) f = Apply.apply(f, env[i], this);
        return f;
    }

    @Override
    public String getName() { return "<builtin application>"; }
}
