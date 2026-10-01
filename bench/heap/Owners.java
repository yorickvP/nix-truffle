import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;

/**
 * Who holds the memory in an HPROF heap dump (see bench/owners.sh): for every Object[], String[],
 * String (with its byte[]) and Thunk (evaluated or pending), the set of fields that reference it
 * ("NixAttrs.values", "Thunk.data", "Object[][0]" for an environment's parent, ...), and the
 * bytes per such set; and which arrays hold evaluated thunks. Sizes assume compact object
 * headers and compressed references.
 */
public class Owners {
    static int idSize;
    static MappedByteBuffer[] maps;
    static final long CHUNK = 1L << 30;
    static long fileLen;

    static int u1(long p) { return maps[(int) (p / CHUNK)].get((int) (p % CHUNK)) & 0xff; }
    static int u2(long p) { return (u1(p) << 8) | u1(p + 1); }
    static int u4(long p) { return (u1(p) << 24) | (u1(p + 1) << 16) | (u1(p + 2) << 8) | u1(p + 3); }
    static long u8(long p) { return ((long) u4(p) << 32) | (u4(p + 4) & 0xffffffffL); }
    static long id(long p) { return idSize == 8 ? u8(p) : u4(p) & 0xffffffffL; }
    static int typeSize(int t) {
        return switch (t) { case 2 -> idSize; case 4, 8 -> 1; case 5, 9 -> 2; case 6, 10 -> 4; case 7, 11 -> 8; default -> throw new IllegalStateException("type " + t); };
    }
    static long align(long n) { return (n + 7) & ~7L; }

    // tracked objects: id -> slot; per slot: type, size (bytes), aux id (String.value / Thunk.code), owner mask
    static final int OBJARR = 1, STRARR = 2, STRING = 3, BYTES = 4, THUNK = 5;
    static long[] keys, aux, masks; static int[] sizes; static byte[] types; static int mask;
    static int slot(long k, boolean insert) {
        int h = Long.hashCode(k * 0x9E3779B97F4A7C15L) & mask;
        while (true) {
            if (keys[h] == 0) { if (!insert) return -1; keys[h] = k; return h; }
            if (keys[h] == k) return h;
            h = (h + 1) & mask;
        }
    }

    static Map<Long, String> strings = new HashMap<>();
    static Map<Long, String> classNames = new HashMap<>();
    record Field(String name, int type) {}
    static Map<Long, Long> superOf = new HashMap<>();
    static Map<Long, List<Field>> fieldsOf = new HashMap<>();
    static long objArrClass = -1, strArrClass = -1;
    static List<String> kinds = new ArrayList<>();
    static Map<String, Integer> kindIdx = new HashMap<>();
    static int kind(String k) {
        Integer i = kindIdx.get(k);
        if (i == null) { i = Math.min(kinds.size(), 63); if (i < 63) kinds.add(k); else if (kinds.size() == 63) kinds.add("other"); kindIdx.put(k, i); }
        return i;
    }
    static Map<Long, Long> codeCounts = new HashMap<>();
    static long doneMarker;

    public static void main(String[] a) throws Exception {
        try (FileChannel ch = new RandomAccessFile(a[0], "r").getChannel()) {
            fileLen = ch.size();
            maps = new MappedByteBuffer[(int) ((fileLen + CHUNK - 1) / CHUNK)];
            for (int i = 0; i < maps.length; i++) maps[i] = ch.map(FileChannel.MapMode.READ_ONLY, i * CHUNK, Math.min(CHUNK, fileLen - i * CHUNK));
        }
        long p = 0;
        while (u1(p) != 0) p++;
        p++;
        idSize = u4(p); p += 12;
        long headerEnd = p;
        int cap = 1 << 27; keys = new long[cap]; aux = new long[cap]; masks = new long[cap]; sizes = new int[cap]; types = new byte[cap]; mask = cap - 1;
        for (int pass = 1; pass <= 3; pass++) {
            p = headerEnd;
            while (p < fileLen) {
                int tag = u1(p); long len = u4(p + 5) & 0xffffffffL; long body = p + 9;
                if (pass == 1 && tag == 0x01) {
                    int n = (int) (len - idSize); byte[] b = new byte[n];
                    for (int i = 0; i < n; i++) b[i] = (byte) u1(body + idSize + i);
                    strings.put(id(body), new String(b, "UTF-8"));
                } else if (pass == 1 && tag == 0x02) {
                    long cid = id(body + 4); String name = strings.get(id(body + 8 + idSize)).replace('/', '.');
                    classNames.put(cid, name);
                    if (name.equals("[Ljava.lang.Object;")) objArrClass = cid;
                    if (name.equals("[Ljava.lang.String;")) strArrClass = cid;
                } else if (tag == 0x0C || tag == 0x1C) {
                    heap(body, body + len, pass);
                }
                p = body + len;
            }
            if (pass == 1) doneMarker = codeCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0L);
            System.err.println("pass " + pass + " done");
        }
        report();
    }

    static Map<String, Long> doneHolders = new HashMap<>();
    static long bit(String k) { Integer i = kindIdx.get(k); return i == null ? 0 : 1L << i; }
    static String describe(long m) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 64; k++) if ((m & (1L << k)) != 0) sb.append(kinds.get(k)).append(' ');
        return sb.toString().trim();
    }

    static void report() {
        doneHolders.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(12)
                .forEach(e -> System.out.printf("done thunks held by %-90s %9d%n", e.getKey(), e.getValue()));
        Map<String, long[]> by = new HashMap<>();
        long doneThunks = 0, pendingThunks = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == 0 || types[i] == BYTES) continue;
            String what = switch (types[i]) {
                case OBJARR -> "Object[]"; case STRARR -> "String[]"; case STRING -> "String+byte[]";
                default -> aux[i] == doneMarker ? "Thunk(done)" : "Thunk(pending)";
            };
            long bytes = sizes[i];
            if (types[i] == STRING) { int b = slot(aux[i], false); if (b >= 0) bytes += sizes[b]; }
            StringBuilder sb = new StringBuilder(what).append(" <- ");
            long m = masks[i];
            if (m == 0) sb.append("(root/unknown)");
            for (int k = 0; k < 64; k++) if ((m & (1L << k)) != 0) sb.append(kinds.get(k)).append(' ');
            long[] acc = by.computeIfAbsent(sb.toString(), x -> new long[2]);
            acc[0] += bytes; acc[1]++;
        }
        by.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(45)
                .forEach(e -> System.out.printf("%8.1f MB %10d  %s%n", e.getValue()[0] / 1e6, e.getValue()[1], e.getKey()));
    }

    static void track(long oid, int type, int size, long auxId) {
        int s = slot(oid, true); types[s] = (byte) type; sizes[s] = size; aux[s] = auxId;
    }

    static void ref(long target, String kind) {
        if (target == 0) return;
        int s = slot(target, false);
        if (s >= 0) masks[s] |= 1L << kind(kind);
    }

    static void heap(long p, long end, int pass) {
        while (p < end) {
            int st = u1(p); p++;
            switch (st) {
                case 0xFF -> p += idSize;
                case 0x01 -> p += 2L * idSize;
                case 0x02, 0x03 -> p += idSize + 8;
                case 0x04, 0x06 -> p += idSize + 4;
                case 0x05, 0x07 -> p += idSize;
                case 0x08 -> p += idSize + 8;
                case 0x20 -> {
                    long cid = id(p); long sup = id(p + 4 + idSize); long q = p + 4 + 7L * idSize + 4;
                    int cpc = u2(q); q += 2;
                    for (int i = 0; i < cpc; i++) { int t = u1(q + 2); q += 3 + typeSize(t); }
                    int sc = u2(q); q += 2;
                    for (int i = 0; i < sc; i++) { int t = u1(q + idSize); q += idSize + 1 + typeSize(t); }
                    int fc = u2(q); q += 2;
                    List<Field> fs = new ArrayList<>();
                    for (int i = 0; i < fc; i++) { fs.add(new Field(strings.get(id(q)), u1(q + idSize))); q += idSize + 1; }
                    if (pass == 1) { superOf.put(cid, sup); fieldsOf.put(cid, fs); }
                    p = q;
                }
                case 0x21 -> {
                    long oid = id(p); long cid = id(p + 4 + idSize); int n = u4(p + 4 + 2L * idSize); long q = p + 8 + 2L * idSize;
                    instance(oid, cid, q, pass);
                    p = q + n;
                }
                case 0x22 -> {
                    long oid = id(p); int n = u4(p + 4 + idSize); long acid = id(p + 8 + idSize); long q = p + 8 + 2L * idSize;
                    if (pass == 1 && (acid == objArrClass || acid == strArrClass)) track(oid, acid == objArrClass ? OBJARR : STRARR, (int) align(12 + 4L * n), 0);
                    if (pass == 3 && acid == objArrClass) {
                        int self = slot(oid, false);
                        long m = self >= 0 ? masks[self] : 0;
                        String holder = (m & ~bit("Object[][0]")) == bit("NixAttrs.values") ? "attrs values"
                                : (m & ~bit("Object[][0]")) == bit("NixList.items") ? "list items"
                                : m == 0 ? "unreferenced array" : "env or other (" + describe(m) + ")";
                        for (int i = 0; i < n; i++) {
                            long t = id(q + (long) i * idSize);
                            int ts = t == 0 ? -1 : slot(t, false);
                            if (ts >= 0 && types[ts] == THUNK && aux[ts] == doneMarker) doneHolders.merge(holder + (i == 0 ? " [0]" : ""), 1L, Long::sum);
                        }
                    }
                    if (pass == 2) {
                        String k0 = acid == objArrClass ? "Object[][0]" : acid == strArrClass ? "String[][i]" : "array";
                        String ki = acid == objArrClass ? "Object[][i]" : k0;
                        for (int i = 0; i < n; i++) ref(id(q + (long) i * idSize), i == 0 ? k0 : ki);
                    }
                    p = q + (long) n * idSize;
                }
                case 0x23 -> {
                    long oid = id(p); int n = u4(p + 4 + idSize); int t = u1(p + 8 + idSize);
                    if (pass == 1 && t == 8) track(oid, BYTES, (int) align(12 + n), 0);
                    p += 9 + idSize + (long) n * typeSize(t);
                }
                default -> throw new IllegalStateException("subtag " + Integer.toHexString(st) + " at " + p);
            }
        }
    }

    static void instance(long oid, long cid, long q, int pass) {
        String cname = classNames.get(cid);
        boolean isString = cname.equals("java.lang.String"), isThunk = cname.equals("nixtruffle.runtime.Thunk");
        String shortName = cname.substring(cname.lastIndexOf('.') + 1);
        long c = cid;
        long code = 0, value = 0;
        while (c != 0 && fieldsOf.containsKey(c)) {
            for (Field fd : fieldsOf.get(c)) {
                if (fd.type == 2) {
                    long t = id(q);
                    if (isString && fd.name.equals("value")) value = t;
                    if (isThunk && fd.name.equals("code")) code = t;
                    if (pass == 2 && !(isString && fd.name.equals("value"))) ref(t, shortName + "." + fd.name);
                }
                q += typeSize(fd.type);
            }
            c = superOf.getOrDefault(c, 0L);
        }
        if (pass == 1 && isString) track(oid, STRING, 24, value);
        if (pass == 1 && isThunk) { track(oid, THUNK, 16, code); codeCounts.merge(code, 1L, Long::sum); }
    }
}
