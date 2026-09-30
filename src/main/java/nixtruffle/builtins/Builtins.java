package nixtruffle.builtins;

import nixtruffle.NixContext;
import nixtruffle.Settings;
import nixtruffle.runtime.Apply;
import nixtruffle.runtime.Arith;
import nixtruffle.runtime.Builtin;
import nixtruffle.runtime.Bytes;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixFunction;
import nixtruffle.runtime.NixLambda;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.NixNull;
import nixtruffle.runtime.NixPath;
import nixtruffle.runtime.NixString;
import nixtruffle.runtime.PartialApp;
import nixtruffle.runtime.Printer;
import nixtruffle.runtime.Thunk;
import nixtruffle.runtime.Values;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static nixtruffle.runtime.Thunk.force;
import static nixtruffle.runtime.Values.attrs;
import static nixtruffle.runtime.Values.bool;
import static nixtruffle.runtime.Values.integer;
import static nixtruffle.runtime.Values.list;
import static nixtruffle.runtime.Values.string;
import static nixtruffle.runtime.Values.stringNoCtx;

/**
 * The primops. Each follows CppNix's implementation ({@code libexpr/primops.cc}): which
 * arguments it forces, in which order, and which errors it raises. That is observable through
 * {@code builtins.tryEval}, which only catches {@code throw}, failed assertions and missing
 * {@code <paths>}: when two arguments fail differently, the first one forced wins.
 *
 * <p>Every builtin receives lazy arguments and returns a value in weak head normal form;
 * lazily-built results (e.g. {@code map}'s elements) use {@link Apply#lazy}.
 */
public final class Builtins {
    private Builtins() {}

    /** A primop and how it is registered: the global name is {@code __name} unless {@code unprefixed}. */
    private record PrimOp(Builtin builtin, boolean unprefixed, String feature) {}

    private static final TreeMap<String, PrimOp> PRIMOPS = new TreeMap<>();

    /** Primops that are visible without {@code builtins.} under their own name. */
    private static final Set<String> UNPREFIXED = Set.of("abort", "baseNameOf", "break", "derivationStrict", "dirOf",
            "fetchGit", "fetchMercurial", "fetchTarball", "fetchTree", "fromTOML", "import", "isNull", "map", "placeholder",
            "removeAttrs", "scopedImport", "throw", "toString");

    static void def(String name, int arity, Builtin.Impl impl) {
        defFeature(name, arity, null, impl);
    }

    /** A primop that only exists with an experimental feature (or {@code "polyglot"}) enabled. */
    static void defFeature(String name, int arity, String feature, Builtin.Impl impl) {
        PRIMOPS.put(name, new PrimOp(new Builtin(name, arity, impl), UNPREFIXED.contains(name), feature));
    }

    static NixException error(String message) {
        return NixException.error(message, null);
    }

    static NixAttrs set(Object... kv) {
        TreeMap<String, Object> map = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return NixAttrs.fromMap(map);
    }

    static NixList nixList(Object v) {
        Object f = force(v);
        return f instanceof NixList l ? l : new NixList(list(f));
    }

    /** {@code state.getAttr}: a required attribute (unforced). */
    static Object required(NixAttrs set, String name) {
        Object v = set.getRaw(name);
        if (v == null) throw error("attribute '" + name + "' missing");
        return v;
    }

    private static final Object[] NO_ARGS = new Object[0];

    /** Calls {@code f} on {@code args}, returning the result in WHNF (CppNix's callFunction). */
    static Object call(Object f, Object... args) {
        Object r = f;
        for (Object a : args) r = Apply.apply(r, a, null);
        return force(r);
    }

    static {
        // -------------------------------------------------------- arithmetic
        def("add", 2, a -> {
            Object x = force(a[0]);
            Object y = force(a[1]);
            if (x instanceof Double || y instanceof Double) return Values.number(x) + Values.number(y);
            return Arith.add(integer(x), integer(y), null);
        });
        def("sub", 2, a -> {
            Object x = force(a[0]);
            Object y = force(a[1]);
            if (x instanceof Double || y instanceof Double) return Values.number(x) - Values.number(y);
            return Arith.sub(integer(x), integer(y), null);
        });
        def("mul", 2, a -> {
            Object x = force(a[0]);
            Object y = force(a[1]);
            if (x instanceof Double || y instanceof Double) return Values.number(x) * Values.number(y);
            return Arith.mul(integer(x), integer(y), null);
        });
        def("div", 2, a -> {
            Object x = force(a[0]);
            Object y = force(a[1]);
            double f2 = Values.number(y);
            if (f2 == 0) throw error("division by zero");
            if (x instanceof Double || y instanceof Double) return Values.number(x) / f2;
            return Arith.div(integer(x), integer(y), null);
        });
        def("lessThan", 2, a -> {
            force(a[0]);
            force(a[1]);
            return Values.lessThan(a[0], a[1], null);
        });
        def("bitAnd", 2, a -> integer(a[0]) & integer(a[1]));
        def("bitOr", 2, a -> integer(a[0]) | integer(a[1]));
        def("bitXor", 2, a -> integer(a[0]) ^ integer(a[1]));
        def("ceil", 1, a -> roundToInt(a[0], Math.ceil(Values.number(a[0]))));
        def("floor", 1, a -> roundToInt(a[0], Math.floor(Values.number(a[0]))));

        // ------------------------------------------------------------- types
        def("typeOf", 1, a -> Values.typeOf(force(a[0])));
        def("isInt", 1, a -> force(a[0]) instanceof Long);
        def("isFloat", 1, a -> force(a[0]) instanceof Double);
        def("isBool", 1, a -> force(a[0]) instanceof Boolean);
        def("isString", 1, a -> NixString.is(force(a[0])));
        def("isPath", 1, a -> force(a[0]) instanceof NixPath);
        def("isNull", 1, a -> force(a[0]) instanceof NixNull);
        def("isAttrs", 1, a -> force(a[0]) instanceof NixAttrs);
        def("isList", 1, a -> force(a[0]) instanceof NixList);
        def("isFunction", 1, a -> Values.typeOf(force(a[0])).equals("lambda"));

        // ----------------------------------------------------------- control
        def("seq", 2, a -> {
            force(a[0]);
            return force(a[1]);
        });
        def("deepSeq", 2, a -> {
            Printer.deepForce(a[0]);
            return force(a[1]);
        });
        def("trace", 2, a -> {
            Object msg = force(a[0]);
            NixContext.get(null).printErr("trace: " + (NixString.is(msg) ? NixString.value(msg) : Printer.show(msg, false)));
            return force(a[1]);
        });
        def("warn", 2, a -> {
            String msg = string(a[0]);
            NixContext.get(null).printErr("evaluation warning: " + msg);
            return force(a[1]);
        });
        def("break", 1, a -> force(a[0]));
        def("throw", 1, a -> {
            throw new NixException.Catchable(Values.coerce(a[0], false, true, new TreeSet<>(), null), null);
        });
        def("abort", 1, a -> {
            throw error("evaluation aborted with the following error message: '" + Values.coerce(a[0], false, true, new TreeSet<>(), null) + "'");
        });
        def("addErrorContext", 2, a -> {
            try {
                return force(a[1]);
            } catch (NixException e) {
                // CppNix coerces the message only when there is an error; if that fails, its error wins.
                Values.coerce(a[0], false, false, new TreeSet<>(), null);
                throw e;
            }
        });
        def("tryEval", 1, a -> {
            try {
                return set("success", true, "value", force(a[0]));
            } catch (NixException.Catchable e) {
                return set("success", false, "value", false);
            }
        });
        def("getEnv", 1, a -> {
            String name = stringNoCtx(a[0]);
            if (NixContext.get(null).settings.getBool("pure-eval")) return "";
            String v = nixtruffle.util.Proc.getenv(Bytes.toJava(name));
            return v == null ? "" : Bytes.fromJava(v);
        });

        // ------------------------------------------------------------- lists
        def("length", 1, a -> (long) list(a[0]).length);
        def("head", 1, a -> {
            NixList l = nixList(a[0]);
            if (l.size() == 0) throw error("'builtins.head' called on an empty list");
            return l.forceAt(0);
        });
        def("tail", 1, a -> {
            Object[] l = list(a[0]);
            if (l.length == 0) throw error("'builtins.tail' called on an empty list");
            return new NixList(Arrays.copyOfRange(l, 1, l.length));
        });
        def("elemAt", 2, a -> {
            long i = integer(a[1]);
            NixList l = nixList(a[0]);
            if (i < 0 || i >= l.size()) throw error("'builtins.elemAt' called with index " + i + " on a list of size " + l.size());
            return l.forceAt((int) i);
        });
        def("map", 2, a -> {
            NixList xs = nixList(a[1]);
            if (xs.size() == 0) return xs;
            Values.function(a[0]);
            Object[] out = new Object[xs.size()];
            for (int i = 0; i < out.length; i++) out[i] = Apply.lazy(a[0], xs.items[i]);
            return new NixList(out);
        });
        def("genList", 2, a -> {
            long n = integer(a[1]);
            if (n < 0 || n > Integer.MAX_VALUE - 8) throw error("cannot create list of size " + n);
            Values.function(a[0]);
            Object[] out = new Object[(int) n];
            for (int i = 0; i < n; i++) out[i] = Apply.lazy(a[0], (long) i);
            return new NixList(out);
        });
        def("filter", 2, a -> {
            NixList xs = nixList(a[1]);
            if (xs.size() == 0) return xs;
            Object f = Values.function(a[0]);
            List<Object> out = new ArrayList<>();
            for (Object x : xs.items) if (bool(call(f, x))) out.add(x);
            return out.size() == xs.size() ? xs : new NixList(out.toArray());
        });
        def("foldl'", 3, a -> {
            Object op = Values.function(a[0]);
            Object[] xs = list(a[2]);
            Object acc = a[1];
            if (xs.length == 0) return force(acc);
            for (Object x : xs) acc = call(op, acc, x);
            return acc;
        });
        def("elem", 2, a -> {
            for (Object x : list(a[1])) if (Values.equal(a[0], x)) return true;
            return false;
        });
        def("all", 2, a -> {
            Object f = Values.function(a[0]);
            for (Object x : list(a[1])) if (!bool(call(f, x))) return false;
            return true;
        });
        def("any", 2, a -> {
            Object f = Values.function(a[0]);
            for (Object x : list(a[1])) if (bool(call(f, x))) return true;
            return false;
        });
        def("concatLists", 1, a -> {
            Object[] lists = list(a[0]);
            List<Object> out = new ArrayList<>();
            for (Object l : lists) out.addAll(Arrays.asList(list(l)));
            return new NixList(out.toArray());
        });
        def("concatMap", 2, a -> {
            Object f = Values.function(a[0]);
            List<Object> out = new ArrayList<>();
            for (Object x : list(a[1])) out.addAll(Arrays.asList(list(call(f, x))));
            return new NixList(out.toArray());
        });
        def("sort", 2, a -> {
            NixList xs = nixList(a[1]);
            if (xs.size() == 0) return xs;
            Object f = Values.function(a[0]);
            Object[] items = new Object[xs.size()];
            for (int i = 0; i < items.length; i++) items[i] = xs.forceAt(i);
            boolean isLessThan = f instanceof Builtin b && b.name.equals("lessThan");
            PeekSort.sort(items, isLessThan ? (x, y) -> Values.lessThan(x, y, null) : (x, y) -> bool(call(f, x, y)));
            return new NixList(items);
        });
        def("partition", 2, a -> {
            Object f = Values.function(a[0]);
            NixList xs = nixList(a[1]);
            List<Object> right = new ArrayList<>();
            List<Object> wrong = new ArrayList<>();
            for (int i = 0; i < xs.size(); i++) {
                Object x = xs.forceAt(i);
                (bool(call(f, x)) ? right : wrong).add(x);
            }
            return set("right", new NixList(right.toArray()), "wrong", new NixList(wrong.toArray()));
        });
        def("groupBy", 2, a -> {
            Object f = Values.function(a[0]);
            TreeMap<String, List<Object>> groups = new TreeMap<>();
            for (Object x : list(a[1])) groups.computeIfAbsent(stringNoCtx(call(f, x)), k -> new ArrayList<>()).add(x);
            TreeMap<String, Object> out = new TreeMap<>();
            groups.forEach((k, v) -> out.put(k, new NixList(v.toArray())));
            return NixAttrs.fromMap(out);
        });

        // ------------------------------------------------------------- attrs
        def("attrNames", 1, a -> {
            NixAttrs s = attrs(a[0]);
            return new NixList(Arrays.copyOf(s.keys, s.size(), Object[].class));
        });
        def("attrValues", 1, a -> new NixList(attrs(a[0]).values.clone()));
        def("hasAttr", 2, a -> {
            String name = stringNoCtx(a[0]);
            return attrs(a[1]).indexOf(name) >= 0;
        });
        def("getAttr", 2, a -> {
            String name = stringNoCtx(a[0]);
            NixAttrs s = attrs(a[1]);
            int i = s.indexOf(name);
            if (i < 0) throw error("attribute '" + name + "' missing");
            return s.forceAt(i);
        });
        def("unsafeGetAttrPos", 2, a -> {
            String name = stringNoCtx(a[0]);
            NixAttrs s = attrs(a[1]);
            int i = s.indexOf(name);
            return i < 0 ? NixNull.INSTANCE : s.position(i);
        });
        def("listToAttrs", 1, a -> {
            Object[] items = list(a[0]);
            // Names first (all of them), then the value of the first element with each name.
            String[] names = new String[items.length];
            NixAttrs[] entries = new NixAttrs[items.length];
            for (int i = 0; i < items.length; i++) {
                entries[i] = attrs(items[i]);
                names[i] = stringNoCtx(required(entries[i], "name"));
            }
            TreeMap<String, Object> map = new TreeMap<>();
            for (int i = 0; i < items.length; i++) {
                if (!map.containsKey(names[i])) map.put(names[i], null);
            }
            // CppNix sorts stably by name and takes the first; errors for a missing value only
            // come from those.
            Map<String, Integer> first = new HashMap<>();
            for (int i = 0; i < items.length; i++) first.putIfAbsent(names[i], i);
            // Each attribute is where its `value` was defined.
            Object[] positions = new Object[map.size()];
            boolean anyPosition = false;
            int n = 0;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                NixAttrs entry = entries[first.get(e.getKey())];
                e.setValue(required(entry, "value"));
                positions[n] = entry.pos(entry.indexOf("value"));
                anyPosition |= positions[n++] != null;
            }
            return new NixAttrs(map.keySet().toArray(new String[0]), map.values().toArray(), anyPosition ? positions : null);
        });
        def("mapAttrs", 2, a -> {
            NixAttrs s = attrs(a[1]);
            Object[] out = new Object[s.size()];
            for (int i = 0; i < out.length; i++) out[i] = Apply.lazy(a[0], s.keys[i], s.values[i]);
            return s.withValues(out);
        });
        def("removeAttrs", 2, a -> {
            NixAttrs s = attrs(a[0]);
            Object[] names = list(a[1]);
            Set<String> remove = new TreeSet<>();
            for (Object name : names) remove.add(stringNoCtx(name));
            return s.without(remove);
        });
        def("intersectAttrs", 2, a -> {
            NixAttrs e1 = attrs(a[0]);
            NixAttrs e2 = attrs(a[1]);
            // Iterate over the smaller set: callPackage intersects a few formals with all of nixpkgs.
            List<Integer> keep = new ArrayList<>();
            if (e1.size() < e2.size()) {
                for (int i = 0; i < e1.size(); i++) {
                    int j = e2.indexOf(e1.keys[i]);
                    if (j >= 0) keep.add(j);
                }
            } else {
                for (int i = 0; i < e2.size(); i++) if (e1.indexOf(e2.keys[i]) >= 0) keep.add(i);
            }
            return e2.select(keep);
        });
        def("catAttrs", 2, a -> {
            String name = stringNoCtx(a[0]);
            List<Object> out = new ArrayList<>();
            for (Object x : list(a[1])) {
                Object v = attrs(x).getRaw(name);
                if (v != null) out.add(v);
            }
            return new NixList(out.toArray());
        });
        def("zipAttrsWith", 2, a -> {
            Values.function(a[0]);
            TreeMap<String, List<Object>> groups = new TreeMap<>();
            for (Object x : list(a[1])) {
                NixAttrs s = attrs(x);
                for (int i = 0; i < s.size(); i++) groups.computeIfAbsent(s.keys[i], k -> new ArrayList<>()).add(s.values[i]);
            }
            TreeMap<String, Object> out = new TreeMap<>();
            groups.forEach((k, v) -> out.put(k, Apply.lazy(a[0], k, new NixList(v.toArray()))));
            return NixAttrs.fromMap(out);
        });
        def("functionArgs", 1, a -> {
            Object f = force(a[0]);
            if (f instanceof Builtin || f instanceof PartialApp) return NixAttrs.EMPTY;
            if (!(f instanceof NixLambda l)) throw error("'functionArgs' requires a function");
            if (!l.info.hasFormals()) return NixAttrs.EMPTY;
            TreeMap<String, Object> map = new TreeMap<>();
            TreeMap<String, Object> positions = new TreeMap<>();
            for (int i = 0; i < l.info.formals().length; i++) {
                map.put(l.info.formals()[i], l.info.hasDefault()[i]);
                positions.put(l.info.formals()[i], l.info.formalPositions()[i]);
            }
            return new NixAttrs(map.keySet().toArray(new String[0]), map.values().toArray(), positions.values().toArray());
        });
        def("genericClosure", 1, a -> {
            NixAttrs args = attrs(a[0]);
            NixList startSet = nixList(required(args, "startSet"));
            if (startSet.size() == 0) return startSet;
            Object op = Values.function(required(args, "operator"));
            ArrayDeque<Object> work = new ArrayDeque<>(Arrays.asList(startSet.items));
            TreeSet<Object> seen = new TreeSet<>((x, y) -> Values.compare(x, y, null));
            List<Object> out = new ArrayList<>();
            while (!work.isEmpty()) {
                NixAttrs e = attrs(work.poll());
                Object key = force(required(e, "key"));
                if (!seen.add(key)) continue;
                out.add(e);
                NixList next = nixList(call(op, e));
                for (int i = 0; i < next.size(); i++) work.add(next.forceAt(i));
            }
            return new NixList(out.toArray());
        });

        // ----------------------------------------------------------- strings
        def("toString", 1, a -> {
            Set<String> context = new TreeSet<>();
            return NixString.make(Values.coerce(a[0], true, false, context, null), context);
        });
        def("stringLength", 1, a -> (long) Values.coerce(a[0], false, true, new TreeSet<>(), null).length());
        def("substring", 3, a -> {
            long start = integer(a[0]);
            if (start < 0) throw error("negative start position in 'substring'");
            long len = integer(a[1]);
            if (len == 0) {
                // Without looking at the string: keeps the context of an empty substring cheap.
                Object s = force(a[2]);
                if (s instanceof NixString ns) return NixString.make("", Arrays.asList(ns.context));
                if (s instanceof String) return "";
            }
            Set<String> context = new TreeSet<>();
            String s = Values.coerce(a[2], false, true, context, null);
            if (start >= s.length()) return NixString.make("", context);
            long end = len < 0 ? s.length() : Math.min(s.length(), start + len);
            return NixString.make(s.substring((int) start, (int) end), context);
        });
        def("concatStringsSep", 2, a -> {
            Set<String> context = new TreeSet<>();
            String sep = string(a[0], context);
            StringBuilder sb = new StringBuilder();
            Object[] items = list(a[1]);
            for (int i = 0; i < items.length; i++) {
                if (i > 0) sb.append(sep);
                sb.append(Values.coerce(items[i], false, true, context, null));
            }
            return NixString.make(sb.toString(), context);
        });
        def("replaceStrings", 3, a -> {
            Object[] from = list(a[0]);
            Object[] to = list(a[1]);
            if (from.length != to.length) throw error("'from' and 'to' arguments passed to builtins.replaceStrings have different lengths");
            String[] fromS = new String[from.length];
            for (int i = 0; i < from.length; i++) fromS[i] = string(from[i]);
            Set<String> context = new TreeSet<>();
            String s = string(a[2], context);
            return NixString.make(replaceStrings(fromS, to, s, context), context);
        });
        def("match", 2, a -> {
            Regex re = Regex.compile(stringNoCtx(a[0]));
            List<String> groups = re.matchFull(string(a[1]));
            return groups == null ? NixNull.INSTANCE : groupList(groups);
        });
        def("split", 2, a -> {
            Regex re = Regex.compile(stringNoCtx(a[0]));
            Object str = force(a[1]);
            String s = string(str);
            List<Object> out = new ArrayList<>();
            int last = 0;
            for (Regex.Match m : re.findAll(s)) {
                out.add(s.substring(last, m.start()));
                out.add(groupList(m.groups()));
                last = m.end();
            }
            if (out.isEmpty()) return new NixList(new Object[] {str});
            out.add(s.substring(last));
            return new NixList(out.toArray());
        });
        def("baseNameOf", 1, a -> {
            Set<String> context = new TreeSet<>();
            String s = Values.coerce(a[0], false, false, context, null);
            return NixString.make(legacyBaseNameOf(s), context);
        });
        def("dirOf", 1, a -> {
            Object v = force(a[0]);
            if (v instanceof NixPath p) {
                if (p.path.equals("/")) return p;
                int slash = p.path.lastIndexOf('/');
                return new NixPath(slash <= 0 ? "/" : p.path.substring(0, slash));
            }
            Set<String> context = new TreeSet<>();
            String s = Values.coerce(v, false, false, context, null);
            int slash = s.lastIndexOf('/');
            String dir = slash < 0 ? "." : slash == 0 ? "/" : s.substring(0, slash);
            return NixString.make(dir, context);
        });
        def("toJSON", 1, a -> Json.toJSON(a[0]));
        def("fromJSON", 1, a -> Json.fromJSON(stringNoCtx(a[0])));
        def("fromTOML", 1, a -> Toml.parse(stringNoCtx(a[0])));
        def("toXML", 1, a -> Xml.toXML(a[0]));
        def("hashString", 2, a -> {
            String algo = hashAlgo(stringNoCtx(a[0]));
            String s = string(a[1]);
            return nixtruffle.store.Hash.of(algo, s).hex();
        });
        def("convertHash", 1, a -> {
            NixAttrs args = attrs(a[0]);
            String hash = stringNoCtx(required(args, "hash"));
            Object algoAttr = args.getRaw("hashAlgo");
            String algo = algoAttr == null ? null : parseHashAlgoOpt(stringNoCtx(algoAttr));
            String format = stringNoCtx(required(args, "toHashFormat"));
            try {
                nixtruffle.store.Hash h = nixtruffle.store.Hash.parseAny(hash, algo);
                return switch (format) {
                    case "base16" -> h.hex();
                    case "nix32", "base32" -> h.base32();
                    case "base64" -> h.base64();
                    case "sri" -> h.sri();
                    default -> throw error("hash format '" + format + "' is not supported");
                };
            } catch (IllegalArgumentException e) {
                throw error(e.getMessage());
            }
        });

        // ---------------------------------------------------------- versions
        def("compareVersions", 2, a -> {
            String v1 = stringNoCtx(a[0]);
            String v2 = stringNoCtx(a[1]);
            return (long) Versions.compare(v1, v2);
        });
        def("splitVersion", 1, a -> new NixList(Versions.split(stringNoCtx(a[0])).toArray()));
        def("parseDrvName", 1, a -> {
            String s = stringNoCtx(a[0]);
            for (int i = 0; i + 1 < s.length(); i++) {
                char c = s.charAt(i + 1);
                if (s.charAt(i) == '-' && !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z')) {
                    return set("name", s.substring(0, i), "version", s.substring(i + 1));
                }
            }
            return set("name", s, "version", "");
        });

        // --------------------------------------------------------- polyglot
        defFeature("polyglotEval", 2, "polyglot", a -> Internals.polyglotEval(string(a[0]), string(a[1])));
        defFeature("polyglotImport", 1, "polyglot", a -> Internals.polyglotImport(string(a[0])));
        defFeature("polyglotExport", 2, "polyglot", a -> Internals.polyglotExport(string(a[0]), a[1]));

        FileBuiltins.install();
        StoreBuiltins.install();
        FetchBuiltins.install();
        WasmBuiltin.install();
        FlakeBuiltins.install();
    }

    /**
     * The base environment of a context: {@code builtins}, and the names visible without it
     * ({@code map}, {@code __add}, ...). Settings decide which experimental primops exist and what
     * {@code nixPath} and {@code traceVerbose} are.
     */
    public static Map<String, Object> createBaseEnv(NixContext ctx) {
        TreeMap<String, Object> builtins = new TreeMap<>();
        Map<String, Object> globals = new LinkedHashMap<>();
        for (Map.Entry<String, PrimOp> e : PRIMOPS.entrySet()) {
            PrimOp p = e.getValue();
            if (p.feature != null) {
                boolean enabled = p.feature.equals("polyglot") ? ctx.settings.getBool("polyglot") : ctx.settings.isEnabled(p.feature);
                if (!enabled) continue;
            }
            builtins.put(e.getKey(), p.builtin);
            globals.put(p.unprefixed ? e.getKey() : "__" + e.getKey(), p.builtin);
        }
        Builtin traceVerbose = new Builtin("traceVerbose", 2, ctx.settings.getBool("trace-verbose")
                ? PRIMOPS.get("trace").builtin.impl : a -> force(a[1]));
        constant(builtins, globals, "traceVerbose", traceVerbose);
        // Impure constants don't exist in pure evaluation.
        if (!ctx.pureEval) {
            constant(builtins, globals, "currentTime", System.currentTimeMillis() / 1000);
            constant(builtins, globals, "currentSystem", Bytes.fromJava(Settings.currentSystem(ctx.settings)));
        }
        // Pretend to be the Lix we compare against, so version-dependent nixpkgs code agrees.
        constant(builtins, globals, "nixVersion", "2.18.3-lix");
        constant(builtins, globals, "storeDir", nixtruffle.store.StorePaths.STORE_DIR);
        constant(builtins, globals, "langVersion", 6L);
        List<Object> nixPath = new ArrayList<>();
        // Pure evaluation has no lookup path.
        for (String entry : ctx.pureEval ? List.<String>of() : ctx.settings.nixPath()) {
            int eq = entry.indexOf('=');
            String prefix = eq < 0 ? "" : entry.substring(0, eq);
            String path = eq < 0 ? entry : entry.substring(eq + 1);
            nixPath.add(set("path", Bytes.fromJava(path), "prefix", Bytes.fromJava(prefix)));
        }
        constant(builtins, globals, "nixPath", new NixList(nixPath.toArray()));

        Builtin derivationValue = new Builtin("derivation", 1, a -> NixContext.get(null).derivationLambda());
        Thunk derivation = Apply.lazy(derivationValue, NixNull.INSTANCE);
        builtins.put("derivation", derivation);
        globals.put("derivation", derivation);
        for (Object[] c : new Object[][] {{"true", true}, {"false", false}, {"null", NixNull.INSTANCE}}) {
            builtins.put((String) c[0], c[1]);
            globals.put((String) c[0], c[1]);
        }

        NixAttrs set = NixAttrs.fromMap(builtins);
        // `builtins.builtins` is `builtins` itself.
        TreeMap<String, Object> withSelf = new TreeMap<>(builtins);
        withSelf.put("builtins", null);
        NixAttrs sorted = NixAttrs.fromMap(withSelf);
        NixAttrs self = new NixAttrs(ctx.language.internKeys(sorted.keys), sorted.values);
        self.values[self.indexOf("builtins")] = self;
        globals.put("builtins", self);
        globals.put(Internals.NAME, Internals.create());
        return globals;
    }

    /** Whether {@code v} is a primop, which is the same object in every context. */
    public static boolean isPrimOp(Object v) {
        for (PrimOp p : PRIMOPS.values()) if (p.builtin == v) return true;
        return false;
    }

    private static void constant(Map<String, Object> builtins, Map<String, Object> globals, String name, Object value) {
        builtins.put(name, value);
        globals.put("__" + name, value);
    }

    // ------------------------------------------------------------- helpers

    /** {@code ceil}/{@code floor}: the result must fit a 64-bit integer; ints must survive the trip. */
    private static long roundToInt(Object arg, double rounded) {
        Object v = force(arg);
        if (!(rounded >= -0x1p63 && rounded < 0x1p63)) {
            if (v instanceof Long l) throw error("the NixInt argument " + l + " caused undefined behavior in previous Nix versions");
            throw error("NixFloat argument " + Values.formatFloat((Double) v) + " is not in the range of NixInt");
        }
        long r = (long) rounded;
        if (v instanceof Long l && l != r) throw error("a loss of precision occurred because the NixInt argument " + l + " was rounded to " + r);
        return r;
    }

    /** {@code baseNameOf}: everything after the last slash, ignoring one trailing slash. */
    static String legacyBaseNameOf(String path) {
        if (path.isEmpty()) return "";
        int last = path.length() - 1;
        if (path.charAt(last) == '/' && last > 0) last--;
        int pos = path.lastIndexOf('/', last);
        pos = pos < 0 ? 0 : pos + 1;
        return path.substring(pos, last + 1);
    }

    /** {@code parseHashAlgo}, or an error for an unknown algorithm. */
    static String hashAlgo(String algo) {
        String a = parseHashAlgoOpt(algo);
        if (a == null) throw error("unknown hash algorithm '" + algo + "', expect 'blake3', 'md5', 'sha1', 'sha256', or 'sha512'");
        return a;
    }

    static String parseHashAlgoOpt(String algo) {
        return switch (algo) {
            case "md5", "sha1", "sha256", "sha512" -> algo;
            default -> null;
        };
    }

    private static String replaceStrings(String[] from, Object[] to, String s, Set<String> context) {
        String[] toS = new String[to.length];
        StringBuilder res = new StringBuilder();
        for (int p = 0; p <= s.length(); ) {
            boolean found = false;
            for (int i = 0; i < from.length; i++) {
                if (s.startsWith(from[i], p)) {
                    found = true;
                    if (toS[i] == null) toS[i] = string(to[i], context);
                    res.append(toS[i]);
                    if (from[i].isEmpty()) {
                        if (p < s.length()) res.append(s.charAt(p));
                        p++;
                    } else {
                        p += from[i].length();
                    }
                    break;
                }
            }
            if (!found) {
                if (p < s.length()) res.append(s.charAt(p));
                p++;
            }
        }
        return res.toString();
    }

    private static NixList groupList(List<String> groups) {
        Object[] out = new Object[groups.size()];
        for (int i = 0; i < out.length; i++) out[i] = groups.get(i) == null ? NixNull.INSTANCE : groups.get(i);
        return new NixList(out);
    }
}
