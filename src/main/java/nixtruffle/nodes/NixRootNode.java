package nixtruffle.nodes;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import nixtruffle.NixLanguage;

/**
 * Root for a file, a lambda body or a thunk body. Its variables live in an {@code Object[]}
 * environment (see {@link NixNode}), not in Truffle frame slots: Truffle frames that closures and
 * thunks keep alive cost several arrays each (primitive slots and tags too), and most of a Nix
 * evaluation's memory is environments that closures and thunks keep alive. The frame has one slot,
 * the environment.
 */
public final class NixRootNode extends RootNode {
    static final int ENV_SLOT = 0;
    private static final FrameDescriptor DESCRIPTOR;

    static {
        FrameDescriptor.Builder b = FrameDescriptor.newBuilder(1);
        b.addSlot(FrameSlotKind.Static, null, null);
        DESCRIPTOR = b.build();
    }

    @Child private NixNode body;
    /** The size of the environment, or 0 if the body has no locals and uses the enclosing one. */
    private final int envSize;
    /** A file's top level, called without arguments (or the REPL scope). */
    private final boolean file;
    /** Null for a thunk: {@link #getName} makes {@code thunk@file:line:column} when asked. */
    private final String name;
    private final SourceSection section;

    public NixRootNode(NixLanguage language, int envSize, boolean file, NixNode body, String name, SourceSection section) {
        super(language, DESCRIPTOR);
        this.envSize = envSize;
        this.file = file;
        this.body = body;
        this.name = name;
        this.section = section;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        Object outer = file ? (args.length > 0 ? args[0] : null) : args[0];
        Object[] env;
        if (envSize == 0) {
            env = (Object[]) outer;
        } else {
            env = new Object[envSize];
            env[0] = outer;
        }
        frame.setObjectStatic(ENV_SLOT, env);
        return body.execute(frame);
    }

    /** As Java text (names and file names are Nix byte strings): for tools, and Pkl's stack traces. */
    @Override
    public String getName() {
        if (name != null) return nixtruffle.runtime.Bytes.toJava(name);
        return "thunk@" + nixtruffle.runtime.Bytes.toJava(section.getSource().getName()) + ":" + section.getStartLine() + ":" + section.getStartColumn();
    }

    @Override
    public SourceSection getSourceSection() { return section; }

    @Override
    public boolean isCloningAllowed() { return true; }

    @Override
    public String toString() { return getName(); }
}
