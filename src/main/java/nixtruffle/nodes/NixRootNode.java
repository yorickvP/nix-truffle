package nixtruffle.nodes;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import nixtruffle.NixLanguage;

/** Root for a file, a lambda body or a thunk body. */
public final class NixRootNode extends RootNode {
    @Child private NixNode body;
    private final String name;
    private final SourceSection section;

    public NixRootNode(NixLanguage language, FrameDescriptor descriptor, NixNode body, String name, SourceSection section) {
        super(language, descriptor);
        this.body = body;
        this.name = name;
        this.section = section;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return body.execute(frame);
    }

    @Override
    public String getName() { return name; }

    @Override
    public SourceSection getSourceSection() { return section; }

    @Override
    public boolean isCloningAllowed() { return true; }

    @Override
    public String toString() { return name; }
}
