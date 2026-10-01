package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;
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
     * Where each attribute was defined, for {@code unsafeGetAttrPos}: null if none of them has a
     * position, else an {@code Object[]} with a {@link Pos} (or null) per attribute, which
     * literals share like their keys, or for the result of {@code //} a {@link Spliced}.
     */
    private final Object positions;

    public NixAttrs(String[] keys, Object[] values) {
        this(keys, values, null);
    }

    /** {@code positions}: null, or an {@code Object[]} of {@link Pos} (or null) per attribute. */
    public NixAttrs(String[] keys, Object[] values, Object positions) {
        this.keys = keys;
        this.values = values;
        this.positions = positions;
    }

    public int size() { return keys.length; }

    /**
     * Recently made key arrays, by their contents: sets with the same keys share one array (only
     * a quarter of a NixOS evaluation's key arrays are distinct, and they take a tenth of its
     * heap), which also keeps attribute selection monomorphic.
     */
    private static final AtomicReferenceArray<String[]> SHARED_KEYS = new AtomicReferenceArray<>(1 << 16);

    /** {@code keys}, or an array with the same contents made before. */
    public static String[] shared(String[] keys) {
        if (keys.length == 0) return EMPTY.keys;
        int hash = 1;
        for (String k : keys) hash = 31 * hash + k.hashCode();
        int slot = spread(hash) & (SHARED_KEYS.length() - 1);
        String[] s = SHARED_KEYS.getAcquire(slot);
        if (s != null && s.length == keys.length && sameContents(s, keys)) return s;
        SHARED_KEYS.setRelease(slot, keys);
        return keys;
    }

    private record Union(String[] a, String[] b, String[] keys) {}

    /** Recent {@code //} results' keys, by the identity of the sides' key arrays. */
    private static final AtomicReferenceArray<Union> UNIONS = new AtomicReferenceArray<>(1 << 13);

    /**
     * The (shared) keys of {@code a // b}, which are {@code keys}: sides' key arrays are mostly
     * shared, so the same two make the same result again and again, and finding it by their
     * identity spares hashing the result's (often thousands of) keys.
     */
    private static String[] union(String[] a, String[] b, String[] keys) {
        int slot = spread(System.identityHashCode(a) * 31 + System.identityHashCode(b)) & (UNIONS.length() - 1);
        Union u = UNIONS.getAcquire(slot);
        if (u != null && u.a == a && u.b == b) return u.keys;
        String[] s = shared(keys);
        UNIONS.setRelease(slot, new Union(a, b, s));
        return s;
    }

    private static boolean sameContents(String[] a, String[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i] && !a[i].equals(b[i])) return false;
        }
        return true;
    }

    /**
     * For big sets that are looked up often (nixpkgs, {@code callPackage}'s {@code intersectAttrs}
     * against it): the key indices (plus one) in an open-addressing hash table, built after a few
     * binary searches, so a lookup compares one string instead of a dozen or more. Until then, a
     * one-element array counting the lookups. Sets that share a key array share the table. Only
     * for big sets, once they have been looked up about as often as building the table costs in
     * binary searches: a NixOS evaluation makes hundreds of package sets, most of them looked up a
     * few times.
     */
    private int[] index;
    private static final int INDEX_MIN_SIZE = 1024;

    /** The index of {@code key}, or a negative number. */
    @TruffleBoundary
    public int indexOf(String key) {
        int[] t = index;
        if (t == null || t.length == 1) {
            if (keys.length < INDEX_MIN_SIZE) return Arrays.binarySearch(keys, key);
            if (t == null) index = t = new int[1];
            if (++t[0] < keys.length >> 5) return Arrays.binarySearch(keys, key);
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

    /** {@link #indexOf} without counting towards a table (for {@code //}'s own lookups). */
    private int find(String key) {
        int[] t = index;
        return t == null || t.length == 1 ? Arrays.binarySearch(keys, key) : indexOf(key);
    }

    private static int[] buildIndex(String[] keys) {
        // At most two thirds full.
        int[] t = new int[Integer.highestOneBit(keys.length + (keys.length >> 1)) << 1];
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
    private NixAttrs sameKeys(Object[] values, Object positions) {
        NixAttrs a = new NixAttrs(keys, values, positions);
        if (index != null && index.length > 1) a.index = index;
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
        String[] keys = shared(sorted.keySet().toArray(new String[0]));
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
        Object[] all = flatPositions();
        Object[] p = all == null ? null : new Object[keys.length];
        int o = 0;
        for (int i = 0; i < keys.length; i++) {
            if (names.contains(keys[i])) continue;
            k[o] = keys[i];
            if (p != null) p[o] = all[i];
            v[o++] = values[i];
        }
        return o == keys.length ? this : new NixAttrs(shared(Arrays.copyOf(k, o)), Arrays.copyOf(v, o), p == null ? null : Arrays.copyOf(p, o));
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
            if (p != null) p[i] = pos(indices.get(i));
        }
        return new NixAttrs(shared(k), v, p);
    }

    /** Where attribute {@code i} was defined: a {@link Pos}, or null. */
    public Pos pos(int i) {
        return posIn(positions, i);
    }

    private static Pos posIn(Object positions, int i) {
        if (positions == null) return null;
        if (positions instanceof Object[] a) return (Pos) a[i];
        return ((Spliced) positions).pos(i);
    }

    /** The positions as an array, null if there are none. */
    private Object[] flatPositions() {
        if (positions == null || positions instanceof Object[]) return (Object[]) positions;
        Object[] out = new Object[keys.length];
        copyPositions(positions, 0, out, 0, keys.length);
        return out;
    }

    private static void copyPositions(Object positions, int start, Object[] out, int at, int count) {
        if (positions == null || count == 0) return;
        if (positions instanceof Object[] a) {
            System.arraycopy(a, start, out, at, count);
            return;
        }
        Spliced s = (Spliced) positions;
        for (int r = s.run(start); count > 0; r++) {
            int n = Math.min(count, s.ends[r] - start);
            int from = s.starts[r], offset = start - s.runStart(r);
            copyPositions(from >= 0 ? s.left : s.right, (from >= 0 ? from : ~from) + offset, out, at, n);
            start += n;
            at += n;
            count -= n;
        }
    }

    private static int depth(Object positions) {
        return positions instanceof Spliced s ? s.depth : 0;
    }

    /**
     * The positions of a {@code //} result, taken in runs from those of its two sides instead of
     * copied: most updates add a few attributes to a big set, and a NixOS evaluation keeps hundreds
     * of thousands of their results (overlays' package sets, the module system's values). Run
     * {@code r} covers this set's attributes from {@code ends[r - 1]} (or 0) to {@code ends[r]},
     * from index {@code starts[r]} of the left side's positions, or {@code ~starts[r]} of the right
     * side's.
     */
    private static final class Spliced {
        /** Lookups go through at most this many levels; deeper ones are flattened. */
        static final int MAX_DEPTH = 8;
        final Object left, right;
        final int[] ends, starts;
        final int depth;

        Spliced(Object left, Object right, int[] ends, int[] starts) {
            this.left = left;
            this.right = right;
            this.ends = ends;
            this.starts = starts;
            this.depth = 1 + Math.max(NixAttrs.depth(left), NixAttrs.depth(right));
        }

        /** The run attribute {@code i} is in. */
        int run(int i) {
            int r = Arrays.binarySearch(ends, i + 1);
            return r >= 0 ? r : -r - 1;
        }

        int runStart(int r) {
            return r == 0 ? 0 : ends[r - 1];
        }

        Pos pos(int i) {
            int r = run(i);
            int from = starts[r], offset = i - runStart(r);
            return from >= 0 ? posIn(left, from + offset) : posIn(right, ~from + offset);
        }
    }

    /**
     * Collects the runs of a {@code //} result's positions, in order; for a small result (most
     * are), straight into an array.
     */
    private static final class Splicer {
        /** Results up to this size get an array: the runs wouldn't be much smaller. */
        static final int FLAT = 32;
        final Object left, right;
        int[] ends, starts;
        Object[] flat;
        int runs, length;

        Splicer(Object left, Object right, int maxLength) {
            this.left = left;
            this.right = right;
            if (maxLength <= FLAT) {
                flat = new Object[maxLength];
            } else {
                ends = new int[8];
                starts = new int[8];
            }
        }

        /** The next {@code count} attributes have the positions of one side's from {@code start}. */
        void take(boolean fromRight, int start, int count) {
            if (count == 0) return;
            if (flat != null) {
                copyPositions(fromRight ? right : left, start, flat, length, count);
                length += count;
                return;
            }
            int from = fromRight ? ~start : start;
            if (runs > 0) {
                int last = starts[runs - 1], lastLength = ends[runs - 1] - (runs == 1 ? 0 : ends[runs - 2]);
                // Continues the last run.
                if (fromRight ? last < 0 && ~last + lastLength == start : last >= 0 && last + lastLength == start) {
                    ends[runs - 1] += count;
                    length += count;
                    return;
                }
            }
            if (runs == ends.length) {
                ends = Arrays.copyOf(ends, runs * 2);
                starts = Arrays.copyOf(starts, runs * 2);
            }
            length += count;
            ends[runs] = length;
            starts[runs++] = from;
        }

        /**
         * The positions: a {@link Spliced}, or an array where that is about as small (two ints a
         * run and a few words of overhead, against a reference an attribute) or would be deep.
         */
        Object build() {
            if (left == null && right == null) return null;
            if (flat != null) return length == flat.length ? flat : Arrays.copyOf(flat, length);
            if (runs * 2 + 16 < length && Math.max(depth(left), depth(right)) < Spliced.MAX_DEPTH) {
                return new Spliced(left, right, Arrays.copyOf(ends, runs), Arrays.copyOf(starts, runs));
            }
            Object[] out = new Object[length];
            for (int r = 0, at = 0; r < runs; r++) {
                int n = ends[r] - at, from = starts[r];
                copyPositions(from >= 0 ? left : right, from >= 0 ? from : ~from, out, at, n);
                at = ends[r];
            }
            return out;
        }
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
            int i = big.find(sk[j]);
            // A key to insert: where.
            if (i < 0) {
                i = Arrays.binarySearch(bk, sk[j]);
                added++;
            }
            at[j] = i;
        }
        // Positions: the big side's on the left, the small side's on the right.
        Splicer p = big.positions != null || small.positions != null ? new Splicer(big.positions, small.positions, bk.length + sk.length) : null;
        if (added == 0) {
            // Only overrides: the big side's keys.
            if (!smallWins) return big;
            Object[] v = big.values.clone();
            int from = 0;
            for (int j = 0; j < sk.length; j++) {
                v[at[j]] = small.values[j];
                if (p != null) {
                    p.take(false, from, at[j] - from);
                    p.take(true, j, 1);
                }
                from = at[j] + 1;
            }
            if (p != null) p.take(false, from, bk.length - from);
            return big.sameKeys(v, p == null ? null : p.build());
        }
        int length = bk.length + added;
        String[] k = new String[length];
        Object[] v = new Object[length];
        int from = 0, o = 0;
        for (int j = 0; j < sk.length; j++) {
            int i = at[j];
            int until = i >= 0 ? i : -i - 1;
            copy(big, from, k, v, p, false, o, until - from);
            o += until - from;
            if (i < 0 || smallWins) {
                k[o] = sk[j];
                v[o] = small.values[j];
                if (p != null) p.take(true, j, 1);
            } else {
                k[o] = bk[i];
                v[o] = big.values[i];
                if (p != null) p.take(false, i, 1);
            }
            o++;
            from = i >= 0 ? i + 1 : until;
        }
        copy(big, from, k, v, p, false, o, bk.length - from);
        return new NixAttrs(union(bk, sk, k), v, p == null ? null : p.build());
    }

    private static void copy(NixAttrs from, int start, String[] k, Object[] v, Splicer p, boolean right, int at, int count) {
        if (count == 0) return;
        System.arraycopy(from.keys, start, k, at, count);
        System.arraycopy(from.values, start, v, at, count);
        if (p != null) p.take(right, start, count);
    }

    /** A merge of the two sorted key arrays, for sides of similar size. */
    private NixAttrs merge(NixAttrs other) {
        String[] a = keys, b = other.keys;
        int n = a.length, m = b.length;
        String[] k = new String[n + m];
        Object[] v = new Object[n + m];
        Splicer p = positions == null && other.positions == null ? null : new Splicer(positions, other.positions, n + m);
        int i = 0, j = 0, o = 0;
        while (i < n && j < m) {
            String x = a[i], y = b[j];
            int c = x == y ? 0 : x.compareTo(y);
            if (c < 0) {
                k[o] = x;
                v[o] = values[i];
                if (p != null) p.take(false, i, 1);
                i++;
            } else {
                if (c == 0) i++;
                k[o] = y;
                v[o] = other.values[j];
                if (p != null) p.take(true, j, 1);
                j++;
            }
            o++;
        }
        copy(this, i, k, v, p, false, o, n - i);
        o += n - i;
        copy(other, j, k, v, p, true, o, m - j);
        o += m - j;
        // Every key of the left side is in the right one: the result is the right side.
        if (o == m) return other;
        Object ps = p == null ? null : p.build();
        // Nothing was added to the left side: share its keys.
        if (o == n) return sameKeys(Arrays.copyOf(v, o), ps);
        return new NixAttrs(union(a, b, o == k.length ? k : Arrays.copyOf(k, o)), o == k.length ? v : Arrays.copyOf(v, o), ps);
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
