import com.oracle.truffle.api.source.Source;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.Pos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

/**
 * Randomized checks of NixAttrs (see tests/attrs.sh): {@code //} against a plain merge of the two
 * sorted key arrays (keys, values and positions, for all kinds of sizes, shared key arrays and
 * missing positions), and lookups (hashed for big sets) against binary search.
 */
public class AttrsCheck {
    static final Source SOURCE = Source.newBuilder("nix", "x".repeat(10000), "f").build();

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42;
        update(new Random(seed), 300_000);
        lookups(new Random(seed), 2000);
    }

    /** {@code a // b} as a merge, the right side winning. */
    static NixAttrs merge(NixAttrs a, NixAttrs b) {
        if (b.keys.length == 0) return a;
        if (a.keys.length == 0) return b;
        String[] k = new String[a.keys.length + b.keys.length];
        Object[] v = new Object[k.length];
        Object[] p = a.positions == null && b.positions == null ? null : new Object[k.length];
        int i = 0, j = 0, o = 0;
        while (i < a.keys.length || j < b.keys.length) {
            int c = i == a.keys.length ? 1 : j == b.keys.length ? -1 : a.keys[i].compareTo(b.keys[j]);
            if (c < 0) {
                k[o] = a.keys[i];
                if (p != null) p[o] = a.positions == null ? null : a.positions[i];
                v[o++] = a.values[i++];
            } else {
                if (c == 0) i++;
                k[o] = b.keys[j];
                if (p != null) p[o] = b.positions == null ? null : b.positions[j];
                v[o++] = b.values[j++];
            }
        }
        return new NixAttrs(Arrays.copyOf(k, o), Arrays.copyOf(v, o), p == null ? null : Arrays.copyOf(p, o));
    }

    static void update(Random r, int cases) {
        for (int t = 0; t < cases; t++) {
            NixAttrs a = random(r, 12, "a", null);
            NixAttrs b = random(r, 12, "b", a.keys);
            NixAttrs want = merge(a, b), got = a.update(b);
            if (!contents(want).equals(contents(got))) {
                fail("a // b\n  a = " + contents(a) + "\n  b = " + contents(b) + "\n  want " + contents(want) + "\n  got  " + contents(got));
            }
            for (int i = 1; i < got.keys.length; i++) if (got.keys[i - 1].compareTo(got.keys[i]) >= 0) fail("unsorted keys " + contents(got));
        }
        System.out.println(cases + " random updates agree with a merge");
    }

    static void lookups(Random r, int sets) {
        long checks = 0;
        for (int t = 0; t < sets; t++) {
            int size = r.nextInt(3000);
            TreeSet<String> ks = new TreeSet<>();
            while (ks.size() < size) ks.add(r.nextInt(3) == 0 ? "pkg-" + r.nextInt(size * 3 + 1) : Integer.toString(r.nextInt(size * 3 + 1), 36));
            String[] keys = ks.toArray(new String[0]);
            NixAttrs a = new NixAttrs(keys, new Object[keys.length]);
            NixAttrs shared = a.withValues(new Object[keys.length]);
            // Enough lookups for big sets to get their tables.
            for (int q = 0; q < 200; q++) {
                String key = new String((r.nextBoolean() && keys.length > 0 ? keys[r.nextInt(keys.length)] : "x" + r.nextInt(100)).toCharArray());
                int want = Arrays.binarySearch(keys, key);
                for (NixAttrs s : new NixAttrs[] {a, shared}) {
                    int got = s.indexOf(key);
                    if ((want >= 0) != (got >= 0) || want >= 0 && want != got) fail("lookup of " + key + " in a set of " + size + ": " + got + " instead of " + want);
                    checks++;
                }
            }
        }
        System.out.println(checks + " lookups agree with binary search");
    }

    static NixAttrs random(Random r, int maxSize, String tag, String[] shareKeys) {
        if (shareKeys != null && r.nextInt(10) == 0) {
            Object[] v = new Object[shareKeys.length];
            for (int i = 0; i < v.length; i++) v[i] = tag + i;
            return new NixAttrs(shareKeys, v, r.nextBoolean() ? null : positions(r, v.length, tag));
        }
        int size = r.nextInt(4) == 0 ? r.nextInt(maxSize * 30 + 1) : r.nextInt(maxSize + 1);
        TreeSet<String> ks = new TreeSet<>();
        int universe = Math.max(4, size * (1 + r.nextInt(3)));
        while (ks.size() < size) ks.add("k" + r.nextInt(universe));
        String[] keys = ks.toArray(new String[0]);
        Object[] v = new Object[keys.length];
        for (int i = 0; i < v.length; i++) v[i] = tag + keys[i];
        return new NixAttrs(keys, v, r.nextBoolean() ? null : positions(r, keys.length, tag));
    }

    static Object[] positions(Random r, int n, String tag) {
        Object[] p = new Object[n];
        for (int i = 0; i < n; i++) p[i] = r.nextInt(3) == 0 ? null : new Pos(SOURCE, tag, i);
        return p;
    }

    static List<Object> contents(NixAttrs a) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < a.keys.length; i++) out.add(List.of(a.keys[i], a.values[i], String.valueOf(a.positions == null ? null : a.positions[i])));
        return out;
    }

    static void fail(String message) {
        System.out.println("FAIL " + message);
        System.exit(1);
    }
}
