package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * An attribute set: sorted keys plus a parallel array of (possibly lazy) values.
 *
 * <p>Attribute set literals share their {@code keys} array between all instances they create, so
 * the key array doubles as a cheap "shape": {@link nixtruffle.nodes.SelectStepNode} caches the
 * index of an attribute per key-array identity.
 */
@ExportLibrary(InteropLibrary.class)
public final class NixAttrs extends NixObject {
    public static final NixAttrs EMPTY = new NixAttrs(new String[0], new Object[0]);

    public final String[] keys;
    public final Object[] values;

    public NixAttrs(String[] keys, Object[] values) {
        this.keys = keys;
        this.values = values;
    }

    public int size() { return keys.length; }

    @TruffleBoundary
    public int indexOf(String key) {
        return Arrays.binarySearch(keys, key);
    }

    @TruffleBoundary
    public static int indexOf(String[] keys, String key) {
        int i = Arrays.binarySearch(keys, key);
        return i < 0 ? -1 : i;
    }

    public Object getRaw(String key) {
        int i = indexOf(key);
        return i < 0 ? null : values[i];
    }

    /** Forced value of {@code key}, or null if absent. */
    public Object get(String key) {
        int i = indexOf(key);
        return i < 0 ? null : forceAt(i);
    }

    public Object forceAt(int i) {
        Object v = values[i];
        if (v instanceof Thunk t) {
            v = t.forceSlow();
            values[i] = v;
        }
        return v;
    }

    @TruffleBoundary
    public static NixAttrs fromMap(Map<String, Object> map) {
        TreeMap<String, Object> sorted = map instanceof TreeMap<String, Object> t ? t : new TreeMap<>(map);
        String[] keys = sorted.keySet().toArray(new String[0]);
        return new NixAttrs(keys, sorted.values().toArray());
    }

    @TruffleBoundary
    public TreeMap<String, Object> toMap() {
        TreeMap<String, Object> map = new TreeMap<>();
        for (int i = 0; i < keys.length; i++) map.put(keys[i], values[i]);
        return map;
    }

    /** {@code this // other}: a merge of two sorted key arrays, right side wins. */
    @TruffleBoundary
    public NixAttrs update(NixAttrs other) {
        if (other.keys.length == 0) return this;
        if (keys.length == 0) return other;
        String[] k = new String[keys.length + other.keys.length];
        Object[] v = new Object[k.length];
        int i = 0, j = 0, o = 0;
        while (i < keys.length || j < other.keys.length) {
            int c = i == keys.length ? 1 : j == other.keys.length ? -1 : keys[i].compareTo(other.keys[j]);
            if (c < 0) {
                k[o] = keys[i];
                v[o++] = values[i++];
            } else {
                if (c == 0) i++;
                k[o] = other.keys[j];
                v[o++] = other.values[j++];
            }
        }
        return new NixAttrs(Arrays.copyOf(k, o), Arrays.copyOf(v, o));
    }

    @ExportMessage
    boolean hasMembers() { return true; }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        return new NixList(Arrays.copyOf(keys, keys.length, Object[].class));
    }

    @ExportMessage
    boolean isMemberReadable(String member) { return indexOf(member) >= 0; }

    @ExportMessage
    Object readMember(String member) throws UnknownIdentifierException {
        int i = indexOf(member);
        if (i < 0) throw UnknownIdentifierException.create(member);
        return forceAt(i);
    }

    @ExportMessage
    boolean isMemberInvocable(String member) {
        int i = indexOf(member);
        return i >= 0 && forceAt(i) instanceof NixFunction;
    }

    @ExportMessage
    Object invokeMember(String member, Object[] arguments) throws UnknownIdentifierException, UnsupportedMessageException, ArityException {
        int i = indexOf(member);
        if (i < 0) throw UnknownIdentifierException.create(member);
        if (!(forceAt(i) instanceof NixFunction fn)) throw UnsupportedMessageException.create();
        return fn.execute(arguments);
    }
}
