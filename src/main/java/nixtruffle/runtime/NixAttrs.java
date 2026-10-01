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
    /**
     * Where each attribute was defined ({@link Pos}, or null), for {@code unsafeGetAttrPos}; null
     * if none of them has a position. Literals share theirs like their keys.
     */
    public final Object[] positions;

    public NixAttrs(String[] keys, Object[] values) {
        this(keys, values, null);
    }

    public NixAttrs(String[] keys, Object[] values, Object[] positions) {
        this.keys = keys;
        this.values = values;
        this.positions = positions;
    }

    public int size() { return keys.length; }

    /**
     * For big sets that are looked up often (nixpkgs, {@code callPackage}'s {@code intersectAttrs}
     * against it): the key indices (plus one) in an open-addressing hash table, built after a few
     * binary searches, so a lookup compares one string instead of a dozen. Sets that share a key
     * array share it.
     */
    private int[] index;
    private int lookups;
    private static final int INDEX_MIN_SIZE = 32;
    private static final int INDEX_AFTER_LOOKUPS = 8;

    /** The index of {@code key}, or a negative number. */
    @TruffleBoundary
    public int indexOf(String key) {
        int[] t = index;
        if (t == null) {
            if (keys.length < INDEX_MIN_SIZE || ++lookups < INDEX_AFTER_LOOKUPS) return Arrays.binarySearch(keys, key);
            index = t = buildIndex(keys);
        }
        int mask = t.length - 1;
        int hash = key.hashCode();
        for (int h = spread(hash) & mask; ; h = (h + 1) & mask) {
            int e = t[h];
            if (e == 0) return -1;
            String k = keys[e - 1];
            if (k == key || k.hashCode() == hash && k.equals(key)) return e - 1;
        }
    }

    private static int[] buildIndex(String[] keys) {
        int[] t = new int[Integer.highestOneBit(keys.length) << 2];
        int mask = t.length - 1;
        for (int i = 0; i < keys.length; i++) {
            int h = spread(keys[i].hashCode()) & mask;
            while (t[h] != 0) h = (h + 1) & mask;
            t[h] = i + 1;
        }
        return t;
    }

    private static int spread(int hash) {
        int x = hash * 0x9E3779B9;
        return x ^ (x >>> 16);
    }

    /** A set with {@code keys} (the same array as this one's): it shares the index. */
    private NixAttrs sameKeys(Object[] values, Object[] positions) {
        NixAttrs a = new NixAttrs(keys, values, positions);
        a.index = index;
        return a;
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

    /** The same keys (and so the same shape) with other values, and no positions ({@code mapAttrs}). */
    public NixAttrs withValues(Object[] newValues) {
        return sameKeys(newValues, null);
    }

    /** {@code removeAttrs}. */
    @TruffleBoundary
    public NixAttrs without(java.util.Set<String> names) {
        String[] k = new String[keys.length];
        Object[] v = new Object[keys.length];
        Object[] p = positions == null ? null : new Object[keys.length];
        int o = 0;
        for (int i = 0; i < keys.length; i++) {
            if (names.contains(keys[i])) continue;
            k[o] = keys[i];
            if (p != null) p[o] = positions[i];
            v[o++] = values[i];
        }
        return o == keys.length ? this : new NixAttrs(Arrays.copyOf(k, o), Arrays.copyOf(v, o), p == null ? null : Arrays.copyOf(p, o));
    }

    /** The attributes at these indices (in increasing order), with their positions. */
    @TruffleBoundary
    public NixAttrs select(java.util.List<Integer> indices) {
        String[] k = new String[indices.size()];
        Object[] v = new Object[indices.size()];
        Object[] p = positions == null ? null : new Object[indices.size()];
        for (int i = 0; i < k.length; i++) {
            k[i] = keys[indices.get(i)];
            v[i] = values[indices.get(i)];
            if (p != null) p[i] = positions[indices.get(i)];
        }
        return new NixAttrs(k, v, p);
    }

    /** Where attribute {@code i} was defined: a {@link Pos}, or null. */
    public Pos pos(int i) {
        return positions == null ? null : (Pos) positions[i];
    }

    /**
     * {@code unsafeGetAttrPos}: {@code { file, line, column }} of attribute {@code i}, or null for
     * attributes that weren't defined in a file (builtins' results, {@code -E} expressions).
     */
    public Object position(int i) {
        Pos p = pos(i);
        return p == null ? NixNull.INSTANCE : p.toValue();
    }

    /**
     * {@code this // other}, the right side winning. Most updates add or override a few
     * attributes of a big set (or the other way around), so when one side is much smaller its keys
     * are looked up by binary search and the big side is copied in blocks, instead of comparing
     * every key. A result that is one of the operands is that operand, and one with the left side's
     * keys shares its key array (so attribute selection stays monomorphic).
     */
    @TruffleBoundary
    public NixAttrs update(NixAttrs other) {
        int n = keys.length, m = other.keys.length;
        if (m == 0) return this;
        if (n == 0 || keys == other.keys) return other;
        if (m <= n >> 3) return insert(this, other, true);
        if (n <= m >> 3) return insert(other, this, false);
        return merge(other);
    }

    /**
     * {@code big // small} if {@code smallWins}, else {@code small // big}: the small side's keys
     * are looked up in the big side, which is copied around them.
     */
    private static NixAttrs insert(NixAttrs big, NixAttrs small, boolean smallWins) {
        String[] bk = big.keys, sk = small.keys;
        int[] at = new int[sk.length];
        int added = 0;
        for (int j = 0; j < sk.length; j++) {
            int i = big.indexOf(sk[j]);
            // A key to insert: where.
            if (i < 0) {
                i = Arrays.binarySearch(bk, sk[j]);
                added++;
            }
            at[j] = i;
        }
        boolean withPositions = big.positions != null || small.positions != null;
        if (added == 0) {
            // Only overrides: the big side's keys.
            if (!smallWins) return big;
            Object[] v = big.values.clone();
            Object[] p = !withPositions ? null : big.positions != null ? big.positions.clone() : new Object[bk.length];
            for (int j = 0; j < sk.length; j++) {
                v[at[j]] = small.values[j];
                if (p != null) p[at[j]] = small.pos(j);
            }
            return big.sameKeys(v, p);
        }
        int length = bk.length + added;
        String[] k = new String[length];
        Object[] v = new Object[length];
        Object[] p = withPositions ? new Object[length] : null;
        int from = 0, o = 0;
        for (int j = 0; j < sk.length; j++) {
            int i = at[j];
            int until = i >= 0 ? i : -i - 1;
            copy(big, from, k, v, p, o, until - from);
            o += until - from;
            if (i < 0 || smallWins) {
                k[o] = sk[j];
                v[o] = small.values[j];
                if (p != null) p[o] = small.pos(j);
            } else {
                k[o] = bk[i];
                v[o] = big.values[i];
                if (p != null) p[o] = big.pos(i);
            }
            o++;
            from = i >= 0 ? i + 1 : until;
        }
        copy(big, from, k, v, p, o, bk.length - from);
        return new NixAttrs(k, v, p);
    }

    private static void copy(NixAttrs from, int start, String[] k, Object[] v, Object[] p, int at, int count) {
        if (count == 0) return;
        System.arraycopy(from.keys, start, k, at, count);
        System.arraycopy(from.values, start, v, at, count);
        if (p != null && from.positions != null) System.arraycopy(from.positions, start, p, at, count);
    }

    /** A merge of the two sorted key arrays, for sides of similar size. */
    private NixAttrs merge(NixAttrs other) {
        String[] a = keys, b = other.keys;
        int n = a.length, m = b.length;
        String[] k = new String[n + m];
        Object[] v = new Object[n + m];
        Object[] p = positions == null && other.positions == null ? null : new Object[n + m];
        int i = 0, j = 0, o = 0;
        while (i < n && j < m) {
            String x = a[i], y = b[j];
            int c = x == y ? 0 : x.compareTo(y);
            if (c < 0) {
                k[o] = x;
                v[o] = values[i];
                if (p != null) p[o] = pos(i);
                i++;
            } else {
                if (c == 0) i++;
                k[o] = y;
                v[o] = other.values[j];
                if (p != null) p[o] = other.pos(j);
                j++;
            }
            o++;
        }
        copy(this, i, k, v, p, o, n - i);
        o += n - i;
        copy(other, j, k, v, p, o, m - j);
        o += m - j;
        // Every key of the left side is in the right one: the result is the right side.
        if (o == m) return other;
        // Nothing was added to the left side: share its keys.
        if (o == n) return sameKeys(Arrays.copyOf(v, o), p == null ? null : Arrays.copyOf(p, o));
        if (o == k.length) return new NixAttrs(k, v, p);
        return new NixAttrs(Arrays.copyOf(k, o), Arrays.copyOf(v, o), p == null ? null : Arrays.copyOf(p, o));
    }

    @ExportMessage
    boolean hasMembers() { return true; }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        return new NixList(Arrays.copyOf(keys, keys.length, Object[].class));
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) { return indexOf(Bytes.fromJava(member)) >= 0; }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        int i = indexOf(Bytes.fromJava(member));
        if (i < 0) throw UnknownIdentifierException.create(member);
        return Foreign.out(forceAt(i));
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberInvocable(String member) {
        int i = indexOf(Bytes.fromJava(member));
        return i >= 0 && forceAt(i) instanceof NixFunction;
    }

    @ExportMessage
    @TruffleBoundary
    Object invokeMember(String member, Object[] arguments) throws UnknownIdentifierException, UnsupportedMessageException, ArityException {
        int i = indexOf(Bytes.fromJava(member));
        if (i < 0) throw UnknownIdentifierException.create(member);
        if (!(forceAt(i) instanceof NixFunction fn)) throw UnsupportedMessageException.create();
        return fn.execute(arguments);
    }
}
