package nixtruffle.fetch;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Input attributes (libfetchers' {@code Attrs}): sorted, with string (byte string), integer
 * ({@link Long}, unsigned in Nix) or Boolean values, or a {@link Lazy} one computed on demand
 * (like {@code revCount}, which can be expensive).
 */
public final class Attrs extends TreeMap<String, Object> {
    /** A value computed when first needed. */
    public static final class Lazy {
        private Supplier<Object> compute;
        private Object value;

        public Lazy(Supplier<Object> compute) {
            this.compute = compute;
        }

        public synchronized Object get() {
            if (compute != null) {
                value = compute.get();
                compute = null;
            }
            return value;
        }
    }

    public Attrs() {}

    public Attrs(Map<String, Object> m) {
        super(m);
    }

    public static Attrs of(Object... kv) {
        Attrs a = new Attrs();
        for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
        return a;
    }

    public Attrs copy() {
        return new Attrs(this);
    }

    private Object resolved(String name) {
        Object v = get(name);
        return v instanceof Lazy l ? l.get() : v;
    }

    public String getStr(String name) {
        Object v = resolved(name);
        if (v == null) return null;
        if (v instanceof String s) return s;
        throw new FetchException("input attribute '" + name + "' is not a string " + Json.write(this));
    }

    public String requireStr(String name) {
        String s = getStr(name);
        if (s == null) throw new FetchException("input attribute '" + name + "' is missing");
        return s;
    }

    public Long getInt(String name) {
        Object v = resolved(name);
        if (v == null) return null;
        if (v instanceof Long l) return l;
        throw new FetchException("input attribute '" + name + "' is not an integer");
    }

    public Boolean getBool(String name) {
        Object v = resolved(name);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        throw new FetchException("input attribute '" + name + "' is not a Boolean");
    }

    public boolean getBool(String name, boolean fallback) {
        Boolean b = getBool(name);
        return b == null ? fallback : b;
    }

    /** {@code attrsToQuery}: every value as a string, Booleans as 1/0. */
    public TreeMap<String, String> toQuery() {
        TreeMap<String, String> q = new TreeMap<>();
        for (String k : keySet()) {
            Object v = resolved(k);
            q.put(k, v instanceof Boolean b ? (b ? "1" : "0") : String.valueOf(v));
        }
        return q;
    }

    /** Equality of the resolved values (lazy ones are compared by value). */
    public boolean sameAs(Attrs other) {
        if (!keySet().equals(other.keySet())) return false;
        for (String k : keySet()) if (!resolved(k).equals(other.resolved(k))) return false;
        return true;
    }

    /** A JSON object, as in lock files: sorted keys, integers, strings, Booleans. */
    public static final class Json {
        private Json() {}

        public static String write(Attrs attrs) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (String k : attrs.keySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(k, sb);
                sb.append(':');
                Object v = attrs.resolved(k);
                if (v instanceof String s) quote(s, sb); else sb.append(v);
            }
            return sb.append('}').toString();
        }

        static void quote(String s, StringBuilder sb) {
            nixtruffle.util.Json.quote(s, sb);
        }

        /** The attributes as a JSON object (for lock files and registries). */
        public static TreeMap<String, Object> toJson(Attrs attrs) {
            TreeMap<String, Object> m = new TreeMap<>();
            for (String k : attrs.keySet()) m.put(k, attrs.resolved(k));
            return m;
        }

        /** {@code jsonToAttrs}: numbers are integers, strings and Booleans as they are. */
        public static Attrs fromJson(Object json) {
            Attrs a = new Attrs();
            for (Map.Entry<String, Object> e : nixtruffle.util.Json.obj(json).entrySet()) {
                Object v = e.getValue();
                if (v instanceof Long || v instanceof String || v instanceof Boolean) {
                    a.put(e.getKey(), v);
                } else if (v instanceof Double d) {
                    a.put(e.getKey(), d.longValue());
                } else {
                    throw new FetchException("unsupported input attribute type in lock file");
                }
            }
            return a;
        }
    }
}
