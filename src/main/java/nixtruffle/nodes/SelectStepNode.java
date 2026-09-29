package nixtruffle.nodes;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import nixtruffle.runtime.Foreign;
import nixtruffle.runtime.NixAttrs;

/**
 * Looks up and forces one attribute. Attrset literals share their sorted key array, so the key
 * array's identity acts as a shape: the attribute's index is cached per (keys, name).
 */
public abstract class SelectStepNode extends Node {
    public static final Object MISSING = new Object();
    public static final Object NOT_ATTRS = new Object();

    @Child private ForceNode force = ForceNode.create();

    public abstract Object execute(Object container, String key);

    public static SelectStepNode create() {
        return SelectStepNodeGen.create();
    }

    @Specialization(guards = {"attrs.keys == cachedKeys", "key == cachedKey"}, limit = "4")
    protected Object doCached(NixAttrs attrs, String key,
                              @Cached(value = "attrs.keys", dimensions = 0) String[] cachedKeys,
                              @Cached("key") String cachedKey,
                              @Cached("indexOf(cachedKeys, cachedKey)") int index) {
        return index < 0 ? MISSING : forceAt(attrs, index);
    }

    @Specialization(replaces = "doCached")
    protected Object doGeneric(NixAttrs attrs, String key) {
        int index = attrs.indexOf(key);
        return index < 0 ? MISSING : forceAt(attrs, index);
    }

    @Fallback
    protected Object doOther(Object container, String key) {
        if (Foreign.isForeign(container)) {
            Object value = Foreign.select(container, key);
            return value == null ? MISSING : value;
        }
        return NOT_ATTRS;
    }

    private Object forceAt(NixAttrs attrs, int index) {
        Object raw = attrs.values[index];
        Object value = force.execute(raw);
        if (value != raw) attrs.values[index] = value;
        return value;
    }

    protected static int indexOf(String[] keys, String key) {
        return NixAttrs.indexOf(keys, key);
    }
}
