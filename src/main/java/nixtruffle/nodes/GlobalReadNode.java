package nixtruffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import nixtruffle.GlobalScope;
import nixtruffle.NixContext;

/** A name in the base environment whose value differs per context ({@code builtins}, {@code derivation}, ...). */
public final class GlobalReadNode extends NixNode {
    private final GlobalScope scope;
    private final int index;

    public GlobalReadNode(GlobalScope scope, int index) {
        this.scope = scope;
        this.index = index;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return NixContext.get(this).globalValue(scope, index);
    }
}
