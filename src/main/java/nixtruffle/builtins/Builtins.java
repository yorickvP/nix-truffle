package nixtruffle.builtins;

import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.source.Source;
import nixtruffle.NixContext;
import nixtruffle.runtime.Apply;
import nixtruffle.runtime.Arith;
import nixtruffle.runtime.Builtin;
import nixtruffle.runtime.Foreign;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixFunction;
import nixtruffle.runtime.NixLambda;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.NixNull;
import nixtruffle.runtime.NixPath;
import nixtruffle.runtime.NixString;
import nixtruffle.runtime.Printer;
import nixtruffle.runtime.ReplPrinter;
import nixtruffle.runtime.Thunk;
import nixtruffle.runtime.Values;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static nixtruffle.runtime.Thunk.force;
import static nixtruffle.runtime.Values.attrs;
import static nixtruffle.runtime.Values.bool;
import static nixtruffle.runtime.Values.integer;
import static nixtruffle.runtime.Values.list;
import static nixtruffle.runtime.Values.string;

/**
 * A modest set of primops. Every builtin receives lazy arguments and returns a value in weak head
 * normal form; lazily-built results (e.g. {@code map}'s elements) use {@link Apply#lazy}.
 */
public final class Builtins {
    private Builtins() {}

    private static final TreeMap<String, Object> BUILTINS = new TreeMap<>();
    private static final Map<String, Object> GLOBALS = new HashMap<>();
    private static final Map<String, Pattern> REGEX_CACHE = new HashMap<>();

    /** A name visible without {@code builtins.} (or null). */
    public static Object global(String name) {
        return GLOBALS.get(name);
    }

    static void def(String name, int arity, Builtin.Impl impl) {
        BUILTINS.put(name, new Builtin(name, arity, impl));
    }

    private static NixException error(String message) {
        return NixException.error(message, null);
    }

    private static NixAttrs set(Object... kv) {
        TreeMap<String, Object> map = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return NixAttrs.fromMap(map);
    }

    private static NixList nixList(Object v) {
        Object f = force(v);
        return f instanceof NixList l ? l : new NixList(list(f));
    }

    private static Object required(NixAttrs set, String name) {
        Object v = set.getRaw(name);
        if (v == null) throw error("attribute '" + name + "' missing");
        return v;
    }

    private static double number(Object v) {
        Object f = force(v);
        if (f instanceof Long l) return l;
        if (f instanceof Double d) return d;
        throw NixException.typeError(f, "a float", null);
    }

    static {
        // ------------------------------------------------------------ values
        BUILTINS.put("true", true);
        BUILTINS.put("false", false);
        BUILTINS.put("null", NixNull.INSTANCE);
        // Pretend to be the Lix we compare against, so version-dependent nixpkgs code agrees.
        BUILTINS.put("nixVersion", "2.18.3-lix");
        BUILTINS.put("langVersion", 6L);
        BUILTINS.put("currentSystem", System.getProperty("os.arch").replace("amd64", "x86_64") + "-" + System.getProperty("os.name").toLowerCase());

        // -------------------------------------------------------- arithmetic
        def("add", 2, a -> Arith.add(force(a[0]), force(a[1]), null));
        def("sub", 2, a -> Arith.sub(force(a[0]), force(a[1]), null));
        def("mul", 2, a -> Arith.mul(force(a[0]), force(a[1]), null));
        def("div", 2, a -> Arith.div(force(a[0]), force(a[1]), null));
        def("lessThan", 2, a -> Values.compare(a[0], a[1], null) < 0);
        def("bitAnd", 2, a -> integer(a[0]) & integer(a[1]));
        def("bitOr", 2, a -> integer(a[0]) | integer(a[1]));
        def("bitXor", 2, a -> integer(a[0]) ^ integer(a[1]));
        def("ceil", 1, a -> (long) Math.ceil(number(a[0])));
        def("floor", 1, a -> (long) Math.floor(number(a[0])));

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
        def("isFunction", 1, a -> force(a[0]) instanceof NixFunction);

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
            NixContext.get(null).err.println("trace: " + (NixString.is(msg) ? NixString.value(msg) : Printer.show(msg, true)));
            return force(a[1]);
        });
        def("throw", 1, a -> {
            throw new NixException.Catchable(string(a[0]), null);
        });
        def("abort", 1, a -> {
            throw error("evaluation aborted with the following error message: '" + string(a[0]) + "'");
        });
        def("tryEval", 1, a -> {
            try {
                return set("success", true, "value", force(a[0]));
            } catch (NixException.Catchable e) {
                return set("success", false, "value", false);
            }
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
            NixList l = nixList(a[0]);
            long i = integer(a[1]);
            if (i < 0 || i >= l.size()) throw error("list index " + i + " is out of bounds");
            return l.forceAt((int) i);
        });
        def("map", 2, a -> {
            Object[] xs = list(a[1]);
            Object[] out = new Object[xs.length];
            for (int i = 0; i < xs.length; i++) out[i] = Apply.lazy(a[0], xs[i]);
            return new NixList(out);
        });
        def("genList", 2, a -> {
            long n = integer(a[1]);
            if (n < 0) throw error("cannot create list of size " + n);
            Object[] out = new Object[(int) n];
            for (int i = 0; i < n; i++) out[i] = Apply.lazy(a[0], (long) i);
            return new NixList(out);
        });
        def("filter", 2, a -> {
            Object f = Values.function(a[0]);
            List<Object> out = new ArrayList<>();
            for (Object x : list(a[1])) if (bool(Apply.apply(f, x, null))) out.add(x);
            return new NixList(out.toArray());
        });
        def("foldl'", 3, a -> {
            Object op = Values.function(a[0]);
            Object acc = a[1];
            for (Object x : list(a[2])) acc = Apply.apply(op, acc, x);
            return force(acc);
        });
        def("elem", 2, a -> {
            for (Object x : list(a[1])) if (Values.equal(a[0], x)) return true;
            return false;
        });
        def("all", 2, a -> {
            for (Object x : list(a[1])) if (!bool(Apply.apply(a[0], x, null))) return false;
            return true;
        });
        def("any", 2, a -> {
            for (Object x : list(a[1])) if (bool(Apply.apply(a[0], x, null))) return true;
            return false;
        });
        def("concatLists", 1, a -> {
            List<Object> out = new ArrayList<>();
            for (Object l : list(a[0])) out.addAll(Arrays.asList(list(l)));
            return new NixList(out.toArray());
        });
        def("concatMap", 2, a -> {
            List<Object> out = new ArrayList<>();
            for (Object x : list(a[1])) out.addAll(Arrays.asList(list(Apply.apply(a[0], x, null))));
            return new NixList(out.toArray());
        });
        def("sort", 2, a -> new NixList(mergeSort(list(a[1]).clone(), Values.function(a[0]))));
        def("partition", 2, a -> {
            List<Object> right = new ArrayList<>();
            List<Object> wrong = new ArrayList<>();
            for (Object x : list(a[1])) (bool(Apply.apply(a[0], x, null)) ? right : wrong).add(x);
            return set("right", new NixList(right.toArray()), "wrong", new NixList(wrong.toArray()));
        });
        def("groupBy", 2, a -> {
            TreeMap<String, List<Object>> groups = new TreeMap<>();
            for (Object x : list(a[1])) groups.computeIfAbsent(string(Apply.apply(a[0], x, null)), k -> new ArrayList<>()).add(x);
            TreeMap<String, Object> out = new TreeMap<>();
            groups.forEach((k, v) -> out.put(k, new NixList(v.toArray())));
            return NixAttrs.fromMap(out);
        });

        // ------------------------------------------------------------- attrs
        def("attrNames", 1, a -> new NixList(Arrays.copyOf(attrs(a[0]).keys, attrs(a[0]).size(), Object[].class)));
        def("attrValues", 1, a -> new NixList(attrs(a[0]).values.clone()));
        def("hasAttr", 2, a -> attrs(a[1]).indexOf(string(a[0])) >= 0);
        def("getAttr", 2, a -> {
            String name = string(a[0]);
            Object v = attrs(a[1]).get(name);
            if (v == null) throw error("attribute '" + name + "' missing");
            return v;
        });
        def("listToAttrs", 1, a -> {
            TreeMap<String, Object> map = new TreeMap<>();
            for (Object e : list(a[0])) {
                NixAttrs entry = attrs(e);
                map.putIfAbsent(string(required(entry, "name")), required(entry, "value"));
            }
            return NixAttrs.fromMap(map);
        });
        def("mapAttrs", 2, a -> {
            NixAttrs s = attrs(a[1]);
            Object[] out = new Object[s.size()];
            for (int i = 0; i < out.length; i++) out[i] = Apply.lazy(a[0], s.keys[i], s.values[i]);
            return new NixAttrs(s.keys, out);
        });
        def("removeAttrs", 2, a -> {
            TreeMap<String, Object> map = attrs(a[0]).toMap();
            for (Object name : list(a[1])) map.remove(string(name));
            return NixAttrs.fromMap(map);
        });
        def("intersectAttrs", 2, a -> {
            NixAttrs e1 = attrs(a[0]);
            NixAttrs e2 = attrs(a[1]);
            // Iterate over the smaller set: callPackage intersects a few formals with all of nixpkgs.
            TreeMap<String, Object> map = new TreeMap<>();
            if (e1.size() < e2.size()) {
                for (int i = 0; i < e1.size(); i++) {
                    int j = e2.indexOf(e1.keys[i]);
                    if (j >= 0) map.put(e2.keys[j], e2.values[j]);
                }
            } else {
                for (int i = 0; i < e2.size(); i++) if (e1.indexOf(e2.keys[i]) >= 0) map.put(e2.keys[i], e2.values[i]);
            }
            return NixAttrs.fromMap(map);
        });
        def("catAttrs", 2, a -> {
            String name = string(a[0]);
            List<Object> out = new ArrayList<>();
            for (Object x : list(a[1])) {
                Object v = attrs(x).getRaw(name);
                if (v != null) out.add(v);
            }
            return new NixList(out.toArray());
        });
        def("zipAttrsWith", 2, a -> {
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
            if (!(f instanceof NixFunction)) throw NixException.typeError(f, "a function", null);
            if (!(f instanceof NixLambda l) || !l.info.hasFormals()) return NixAttrs.EMPTY;
            TreeMap<String, Object> map = new TreeMap<>();
            for (int i = 0; i < l.info.formals().length; i++) map.put(l.info.formals()[i], l.info.hasDefault()[i]);
            return NixAttrs.fromMap(map);
        });
        def("genericClosure", 1, a -> {
            NixAttrs args = attrs(a[0]);
            ArrayDeque<Object> work = new ArrayDeque<>(Arrays.asList(list(required(args, "startSet"))));
            Object op = required(args, "operator");
            TreeSet<Object> seen = new TreeSet<>((x, y) -> Values.compare(x, y, null));
            List<Object> out = new ArrayList<>();
            while (!work.isEmpty()) {
                NixAttrs e = attrs(work.poll());
                if (!seen.add(force(required(e, "key")))) continue;
                out.add(e);
                work.addAll(Arrays.asList(list(Apply.apply(op, e, null))));
            }
            return new NixList(out.toArray());
        });

        // ----------------------------------------------------------- strings
        def("toString", 1, a -> {
            Set<String> context = new TreeSet<>();
            return NixString.make(Values.coerce(a[0], true, false, context, null), context);
        });
        def("stringLength", 1, a -> (long) Values.coerce(a[0], false, true, null, null).getBytes(StandardCharsets.UTF_8).length);
        def("substring", 3, a -> {
            long start = integer(a[0]);
            long len = integer(a[1]);
            Set<String> context = new TreeSet<>();
            String s = Values.coerce(a[2], false, true, context, null);
            if (start < 0) throw error("negative start position in 'substring'");
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
            Set<String> context = new TreeSet<>();
            String s = string(a[2], context);
            return NixString.make(replaceStrings(list(a[0]), list(a[1]), s, context), context);
        });
        def("match", 2, a -> {
            Matcher m = regex(string(a[0])).matcher(string(a[1]));
            return m.matches() ? groups(m) : NixNull.INSTANCE;
        });
        def("split", 2, a -> {
            String s = string(a[1]);
            Matcher m = regex(string(a[0])).matcher(s);
            List<Object> out = new ArrayList<>();
            int last = 0;
            while (m.find()) {
                out.add(s.substring(last, m.start()));
                out.add(groups(m));
                last = m.end();
            }
            out.add(s.substring(last));
            return new NixList(out.toArray());
        });
        def("baseNameOf", 1, a -> {
            Set<String> context = new TreeSet<>();
            String s = Values.coerce(a[0], false, false, context, null);
            if (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length() - 1);
            return NixString.make(s.substring(s.lastIndexOf('/') + 1), context);
        });
        def("dirOf", 1, a -> {
            Object v = force(a[0]);
            Set<String> context = new TreeSet<>();
            String s = Values.coerce(v, false, false, context, null);
            int slash = s.lastIndexOf('/');
            String dir = slash < 0 ? "." : slash == 0 ? "/" : s.substring(0, slash);
            return v instanceof NixPath ? new NixPath(dir) : NixString.make(dir, context);
        });
        def("toJSON", 1, a -> Json.toJSON(a[0]));
        def("fromJSON", 1, a -> Json.fromJSON(string(a[0])));

        // ---------------------------------------------------------------- io
        def("import", 1, a -> importPath(a[0]));
        def("readFile", 1, a -> {
            try {
                return new String(file(a[0]).readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw error("cannot read file: " + e.getMessage());
            }
        });
        def("pathExists", 1, a -> file(a[0]).exists());
        def("readDir", 1, a -> {
            TreeMap<String, Object> map = new TreeMap<>();
            try {
                for (TruffleFile child : file(a[0]).list()) {
                    String type = child.isSymbolicLink() ? "symlink" : child.isDirectory() ? "directory" : child.isRegularFile() ? "regular" : "unknown";
                    map.put(child.getName(), type);
                }
            } catch (IOException e) {
                throw error("cannot read directory: " + e.getMessage());
            }
            return NixAttrs.fromMap(map);
        });
        def("getEnv", 1, a -> {
            String v = System.getenv(string(a[0]));
            return v == null ? "" : v;
        });

        // --------------------------------------------------------- polyglot
        def("polyglotEval", 2, a -> polyglotEval(string(a[0]), string(a[1])));
        def("polyglotImport", 1, a -> {
            String name = string(a[0]);
            Object v = NixContext.get(null).env.importSymbol(name);
            if (v == null) throw error("no polyglot binding named '" + name + "'");
            return Foreign.toNix(v);
        });
        def("polyglotExport", 2, a -> {
            Object v = force(a[1]);
            NixContext.get(null).env.exportSymbol(string(a[0]), v);
            return v;
        });

        // -------------------------------------------------------------- misc
        def("addErrorContext", 2, a -> force(a[1]));
        def("traceVerbose", 2, a -> force(a[1]));
        def("break", 1, a -> force(a[0]));
        def("unsafeGetAttrPos", 2, a -> NixNull.INSTANCE);
        def("toPath", 1, a -> Values.coerceToString(a[0], false, null));
        def("compareVersions", 2, a -> (long) Versions.compare(string(a[0]), string(a[1])));
        def("splitVersion", 1, a -> new NixList(Versions.split(string(a[0])).toArray()));
        def("parseDrvName", 1, a -> {
            String s = string(a[0]);
            for (int i = 0; i + 1 < s.length(); i++) {
                if (s.charAt(i) == '-' && !Character.isLetter(s.charAt(i + 1))) {
                    return set("name", s.substring(0, i), "version", s.substring(i + 1));
                }
            }
            return set("name", s, "version", "");
        });
        StoreBuiltins.install();

        def("hashString", 2, a -> {
            String algo = switch (string(a[0])) {
                case "md5" -> "MD5";
                case "sha1" -> "SHA-1";
                case "sha256" -> "SHA-256";
                case "sha512" -> "SHA-512";
                default -> throw error("unknown hash algorithm '" + string(a[0]) + "'");
            };
            try {
                return java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance(algo).digest(string(a[1]).getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw error(e.getMessage());
            }
        });
        def("readFileType", 1, a -> {
            TruffleFile f = file(a[0]);
            return f.isSymbolicLink() ? "symlink" : f.isDirectory() ? "directory" : f.isRegularFile() ? "regular" : "unknown";
        });
        BUILTINS.put("storeDir", "/nix/store");
        BUILTINS.put("nixPath", NixList.EMPTY);

        // Store, derivations and fetchers are out of scope; they exist so that code mentioning them
        // still resolves (Nix resolves variables statically), and fail only when actually called.
        def("fromTOML", 1, a -> Toml.parse(string(a[0])));
        for (String name : List.of("fetchGit", "fetchMercurial", "fetchTarball", "fetchTree", "fetchurl",
                "scopedImport", "toXML", "findFile")) {
            def(name, 1, a -> {
                throw error("builtins." + name + " is not supported by nix-truffle");
            });
        }

        // Not real Nix builtins: rendering for the CLI, and the REPL's entry points.
        def("__show", 1, a -> Printer.show(a[0], true));
        def("__replShow", 2, a -> ReplPrinter.show(a[1], (int) integer(a[0])));
        def("__replEval", 2, a -> {
            NixAttrs scope = attrs(a[0]);
            return parseRepl(scope, string(a[1])).call(scope);
        });
        def("__replBind", 3, a -> {
            // Parsed now (so syntax errors show up immediately), evaluated lazily in the old scope.
            NixAttrs scope = attrs(a[0]);
            TreeMap<String, Object> map = scope.toMap();
            map.put(string(a[1]), new Thunk(parseRepl(scope, string(a[2])), scope));
            return NixAttrs.fromMap(map);
        });
        def("__replGlobals", 1, a -> new NixList(GLOBALS.keySet().stream().filter(n -> !n.startsWith("__")).sorted().toArray()));

        NixAttrs builtins = NixAttrs.fromMap(new TreeMap<>(BUILTINS));
        BUILTINS.put("builtins", builtins);
        NixAttrs withSelf = NixAttrs.fromMap(new TreeMap<>(BUILTINS));
        withSelf.values[withSelf.indexOf("builtins")] = withSelf;

        for (Map.Entry<String, Object> e : BUILTINS.entrySet()) {
            if (!e.getKey().startsWith("__")) GLOBALS.put("__" + e.getKey(), e.getValue());
        }
        for (String name : List.of("true", "false", "null", "import", "throw", "abort", "map", "toString", "isNull",
                "removeAttrs", "baseNameOf", "dirOf", "break", "derivation", "derivationStrict", "fetchGit",
                "fetchMercurial", "fetchTarball", "fetchTree", "fromTOML", "placeholder", "scopedImport")) {
            GLOBALS.put(name, BUILTINS.get(name));
        }
        GLOBALS.put("builtins", withSelf);
    }

    // ------------------------------------------------------------- helpers

    private static Object[] mergeSort(Object[] xs, Object lessThan) {
        if (xs.length <= 1) return xs;
        Object[] left = mergeSort(Arrays.copyOfRange(xs, 0, xs.length / 2), lessThan);
        Object[] right = mergeSort(Arrays.copyOfRange(xs, xs.length / 2, xs.length), lessThan);
        int i = 0, j = 0, o = 0;
        while (i < left.length && j < right.length) {
            // Stable: only take from the right when it is strictly smaller.
            xs[o++] = bool(Apply.apply(lessThan, right[j], left[i])) ? right[j++] : left[i++];
        }
        while (i < left.length) xs[o++] = left[i++];
        while (j < right.length) xs[o++] = right[j++];
        return xs;
    }

    private static String replaceStrings(Object[] from, Object[] to, String s, Set<String> context) {
        if (from.length != to.length) throw error("'from' and 'to' arguments passed to builtins.replaceStrings have different lengths");
        String[] fromS = new String[from.length];
        for (int i = 0; i < from.length; i++) fromS[i] = string(from[i]);
        String[] toS = new String[to.length];
        StringBuilder res = new StringBuilder();
        for (int p = 0; p <= s.length(); ) {
            boolean found = false;
            for (int i = 0; i < fromS.length; i++) {
                if (s.startsWith(fromS[i], p)) {
                    found = true;
                    if (toS[i] == null) toS[i] = string(to[i], context);
                    res.append(toS[i]);
                    if (fromS[i].isEmpty()) {
                        if (p < s.length()) res.append(s.charAt(p));
                        p++;
                    } else {
                        p += fromS[i].length();
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

    /** POSIX extended regular expressions, translated to java.util.regex. */
    private static Pattern regex(String posix) {
        return REGEX_CACHE.computeIfAbsent(posix, r -> {
            String java = r.replace("[:alpha:]", "\\p{Alpha}").replace("[:digit:]", "\\p{Digit}")
                    .replace("[:alnum:]", "\\p{Alnum}").replace("[:space:]", "\\s").replace("[:upper:]", "\\p{Upper}")
                    .replace("[:lower:]", "\\p{Lower}").replace("[:punct:]", "\\p{Punct}").replace("[:xdigit:]", "\\p{XDigit}");
            try {
                return Pattern.compile(java);
            } catch (RuntimeException e) {
                throw error("invalid regular expression '" + r + "'");
            }
        });
    }

    private static NixList groups(Matcher m) {
        Object[] out = new Object[m.groupCount()];
        for (int i = 0; i < out.length; i++) {
            String g = m.group(i + 1);
            out[i] = g == null ? NixNull.INSTANCE : g;
        }
        return new NixList(out);
    }

    static TruffleFile file(Object pathArg) {
        Object v = force(pathArg);
        String path = v instanceof NixPath p ? p.path : Values.coerceToString(v, false, null);
        return NixContext.get(null).env.getPublicTruffleFile(path);
    }

    /** {@code import}: .nix files are parsed and evaluated once; other extensions go to their Truffle language. */
    private static Object importPath(Object pathArg) {
        NixContext ctx = NixContext.get(null);
        Object p = force(pathArg);
        if (p instanceof NixPath np && np.path.startsWith("/__corepkgs__/")) {
            Object cached = ctx.importCache.get(np.path);
            if (cached != null) return cached;
            String text = NixContext.corepkg(np.path.substring("/__corepkgs__/".length()));
            if (text == null) throw error("file '" + np.path + "' does not exist");
            Object result = ctx.language.parseSource(Source.newBuilder("nix", text, np.path).build()).call();
            ctx.importCache.put(np.path, result);
            return result;
        }
        TruffleFile file = file(p);
        if (file.isDirectory()) file = file.resolve("default.nix");
        String key;
        try {
            key = file.getCanonicalFile().getPath();
        } catch (IOException e) {
            throw error("cannot import '" + file.getPath() + "': file does not exist");
        }
        Object cached = ctx.importCache.get(key);
        if (cached != null) return cached;
        String name = file.getName() == null ? "" : file.getName();
        String language = name.endsWith(".nix") ? "nix"
                : name.endsWith(".js") || name.endsWith(".mjs") ? "js"
                : name.endsWith(".py") ? "python"
                : name.endsWith(".rb") ? "ruby" : "nix";
        Object result;
        try {
            Source source = Source.newBuilder(language, file).build();
            result = language.equals("nix") ? ctx.language.parseSource(source).call() : Foreign.toNix(ctx.env.parsePublic(source).call());
        } catch (IOException e) {
            throw error("cannot import '" + key + "': " + e.getMessage());
        }
        ctx.importCache.put(key, result);
        return result;
    }

    private static com.oracle.truffle.api.RootCallTarget parseRepl(NixAttrs scope, String code) {
        Source source = Source.newBuilder("nix", code, "«repl»").build();
        return NixContext.get(null).language.parseRepl(source, new java.util.HashSet<>(Arrays.asList(scope.keys)));
    }

    private static Object polyglotEval(String language, String code) {
        NixContext ctx = NixContext.get(null);
        if (!ctx.env.getPublicLanguages().containsKey(language)) {
            throw error("polyglot language '" + language + "' is not available (have: " + ctx.env.getPublicLanguages().keySet() + ")");
        }
        Source source = Source.newBuilder(language, code, "polyglotEval." + language).build();
        return Foreign.toNix(ctx.env.parsePublic(source).call());
    }
}
