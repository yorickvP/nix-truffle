package nixtruffle.builtins;

import com.oracle.truffle.api.source.Source;
import nixtruffle.NixContext;
import nixtruffle.runtime.Apply;
import nixtruffle.runtime.Builtin;
import nixtruffle.runtime.Bytes;
import nixtruffle.runtime.Foreign;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixLambda;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.Printer;
import nixtruffle.runtime.ReplPrinter;
import nixtruffle.runtime.Thunk;
import nixtruffle.runtime.Values;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;

import static nixtruffle.runtime.Thunk.force;
import static nixtruffle.runtime.Values.attrs;
import static nixtruffle.runtime.Values.bool;
import static nixtruffle.runtime.Values.integer;
import static nixtruffle.runtime.Values.string;

/**
 * The launcher's entry points (CLI, REPL, nix-pbt server), in a global that isn't part of {@code
 * builtins}. Output that must be byte-exact is returned as a host {@code byte[]}, and source text
 * can be passed in as one, since strings crossing the polyglot boundary are Java text.
 */
final class Internals {
    private Internals() {}

    static final String NAME = "__nixTruffle";

    private static NixException error(String message) {
        return NixException.error(message, null);
    }

    private static Object bytesOut(String s) {
        return NixContext.get(null).env.asGuestValue(Bytes.output(s));
    }

    private static byte[] bytesIn(Object v) {
        Object f = force(v);
        var env = NixContext.get(null).env;
        if (env.isHostObject(f) && env.asHostObject(f) instanceof byte[] b) return b;
        return Bytes.get(string(f));
    }

    static NixAttrs create() {
        TreeMap<String, Object> m = new TreeMap<>();
        put(m, "show", 1, a -> bytesOut(Printer.show(a[0], true)));
        put(m, "toJSON", 1, a -> bytesOut(string(Json.toJSON(a[0]))));
        put(m, "evalBytes", 1, a -> {
            String text = Bytes.of(bytesIn(a[0]));
            return NixContext.get(null).language.parse(text, Bytes.fromJava("«string»"), null, null, null).call();
        });
        put(m, "importFile", 1, a -> FileBuiltins.importFile(Bytes.of(bytesIn(a[0]))));
        put(m, "nixEval", 1, a -> bytesOut(CliEval.run(nixtruffle.util.Json.obj(nixtruffle.util.Json.parse(Bytes.of(bytesIn(a[0])))))));
        put(m, "globals", 1, a -> {
            nixtruffle.GlobalScope g = NixContext.get(null).globalScope();
            Object[] names = new Object[g.size()];
            for (int i = 0; i < names.length; i++) names[i] = g.name(i);
            return new nixtruffle.runtime.NixList(names);
        });
        put(m, "lspEval", 2, a -> {
            Map<String, Object> r = nixtruffle.util.Json.obj(nixtruffle.util.Json.parse(Bytes.of(bytesIn(a[0]))));
            if (Boolean.TRUE.equals(r.get("withs"))) {
                return nixtruffle.lsp.Evaluate.withs(nixtruffle.util.Json.str(r.get("file")), nixtruffle.util.Json.str(r.get("text")),
                        ((Number) r.get("offset")).intValue(), a[1]);
            }
            return nixtruffle.lsp.Evaluate.at(nixtruffle.util.Json.str(r.get("file")), nixtruffle.util.Json.str(r.get("text")),
                    ((Number) r.get("offset")).intValue(), nixtruffle.util.Json.str(r.get("expression")), a[1]);
        });
        put(m, "importsDuring", 1, a -> {
            // { value = f null; files = [ what evaluating it imported ]; }
            // On the main thread only (Builtin's MAIN_ONLY): one recording at a time. With parallel
            // evaluation, workers that evaluate ahead may import into another recording: the
            // language server's context has eval-cores = 1.
            NixContext ctx = NixContext.get(null);
            java.util.Set<String> outer = ctx.importRecorder;
            java.util.Set<String> recorder = java.util.concurrent.ConcurrentHashMap.newKeySet();
            ctx.importRecorder = recorder;
            Object value;
            try {
                value = nixtruffle.runtime.Thunk.force(nixtruffle.runtime.Apply.apply(a[0], nixtruffle.runtime.NixNull.INSTANCE, null));
            } finally {
                ctx.importRecorder = outer;
                if (outer != null) outer.addAll(recorder);
            }
            TreeMap<String, Object> out = new TreeMap<>();
            out.put("value", value);
            out.put("files", new nixtruffle.runtime.NixList(new java.util.TreeSet<>(recorder).toArray()));
            return nixtruffle.runtime.NixAttrs.fromMap(out);
        });
        put(m, "lambdaPos", 1, a -> {
            // A function's position ({ file, line, column }), for the doc comment before it.
            if (!(nixtruffle.runtime.Thunk.force(a[0]) instanceof nixtruffle.runtime.NixLambda l)) return nixtruffle.runtime.NixNull.INSTANCE;
            com.oracle.truffle.api.source.SourceSection sec = l.target.getRootNode().getSourceSection();
            if (sec == null || !sec.isAvailable()) return nixtruffle.runtime.NixNull.INSTANCE;
            TreeMap<String, Object> pos = new TreeMap<>();
            pos.put("file", sec.getSource().getName());
            pos.put("line", (long) sec.getStartLine());
            pos.put("column", (long) sec.getStartColumn());
            return nixtruffle.runtime.NixAttrs.fromMap(pos);
        });
        put(m, "replAdd", 2, a -> attrs(a[0]).update(attrs(a[1])));
        put(m, "replLoad", 1, a -> CliEval.replValue(nixtruffle.util.Json.obj(nixtruffle.util.Json.parse(Bytes.of(bytesIn(a[0]))))));
        put(m, "flakeLock", 1, a -> {
            FlakeBuiltins.lock(Bytes.of(bytesIn(a[0])));
            return nixtruffle.runtime.NixNull.INSTANCE;
        });
        put(m, "autoCall", 2, a -> autoCall(attrs(a[0]), a[1]));
        put(m, "findAttrPath", 3, a -> findAttrPath(attrs(a[0]), string(a[1]), a[2]));
        put(m, "instantiate", 3, a -> instantiate(bool(a[0]), attrs(a[1]), a[2]));
        put(m, "replShow", 2, a -> ReplPrinter.show(a[1], (int) integer(a[0])));
        put(m, "replEval", 2, a -> {
            NixAttrs scope = attrs(a[0]);
            return parseRepl(scope, string(a[1])).call(scope);
        });
        put(m, "replBind", 3, a -> {
            // Parsed now (so syntax errors show up immediately), evaluated lazily in the old scope.
            NixAttrs scope = attrs(a[0]);
            TreeMap<String, Object> map = scope.toMap();
            map.put(string(a[1]), new Thunk(parseRepl(scope, string(a[2])), scope));
            return NixAttrs.fromMap(map);
        });
        return NixAttrs.fromMap(m);
    }

    private static void put(TreeMap<String, Object> m, String name, int arity, Builtin.Impl impl) {
        m.put(name, new Builtin(NAME + "." + name, arity, impl));
    }

    private static com.oracle.truffle.api.RootCallTarget parseRepl(NixAttrs scope, String code) {
        return NixContext.get(null).language.parse(code, Bytes.fromJava("«repl»"), null, null, new HashSet<>(Arrays.asList(scope.keys)));
    }

    // ------------------------------------------------------------ polyglot

    static Object polyglotEval(String language, String code) {
        NixContext ctx = NixContext.get(null);
        String lang = Bytes.toJava(language);
        if (!ctx.env.getPublicLanguages().containsKey(lang)) {
            throw error("polyglot language '" + language + "' is not available (have: " + Bytes.fromJava(ctx.env.getPublicLanguages().keySet().toString()) + ")");
        }
        Source source = Source.newBuilder(lang, Bytes.toJava(code), "polyglotEval." + lang).build();
        return Foreign.toNix(ctx.env.parsePublic(source).call());
    }

    static Object polyglotImport(String name) {
        Object v = NixContext.get(null).env.importSymbol(Bytes.toJava(name));
        if (v == null) throw error("no polyglot binding named '" + name + "'");
        return Foreign.toNix(v);
    }

    static Object polyglotExport(String name, Object value) {
        Object v = force(value);
        NixContext.get(null).env.exportSymbol(Bytes.toJava(name), Foreign.out(v));
        return v;
    }

    // ----------------------------------------------------------------- CLI

    /** nix-instantiate's autoCallFunction: call functions with formals using the --arg values. */
    static Object autoCall(NixAttrs autoArgs, Object value) {
        Object v = force(value);
        if (v instanceof NixAttrs a && a.getRaw("__functor") != null) {
            return autoCall(autoArgs, Apply.apply(a.get("__functor"), a, null));
        }
        if (!(v instanceof NixLambda l) || !l.info.hasFormals()) return v;
        TreeMap<String, Object> args = new TreeMap<>();
        if (l.info.ellipsis()) {
            args.putAll(autoArgs.toMap());
        } else {
            for (int i = 0; i < l.info.formals().length; i++) {
                String name = l.info.formals()[i];
                Object given = autoArgs.getRaw(name);
                if (given != null) {
                    args.put(name, given);
                } else if (!l.info.hasDefault()[i]) {
                    throw error("cannot evaluate a function that has an argument without a value ('" + name + "')");
                }
            }
        }
        return Apply.apply(l, NixAttrs.fromMap(args), null);
    }

    /** {@code -A a.b.0}: auto-calls functions along the way, numbers index lists. */
    private static Object findAttrPath(NixAttrs autoArgs, String path, Object root) {
        Object v = force(root);
        if (path.isEmpty()) return v;
        for (String attr : path.split("\\.")) {
            v = autoCall(autoArgs, v);
            if (v instanceof NixList l && attr.matches("[0-9]+")) {
                int i = Integer.parseInt(attr);
                if (i >= l.size()) throw error("list index " + i + " in selection path '" + path + "' is out of range");
                v = l.forceAt(i);
            } else if (v instanceof NixAttrs a) {
                v = a.get(attr);
                if (v == null) throw error("attribute '" + attr + "' in selection path '" + path + "' not found");
            } else {
                throw error("the expression selected by the selection path '" + path + "' should be a set but is " + Values.typeName(v));
            }
        }
        return v;
    }

    /** nix-instantiate: collect derivations (like getDerivations), force their .drv, optionally write them. */
    private static Object instantiate(boolean write, NixAttrs autoArgs, Object value) {
        List<NixAttrs> drvs = new ArrayList<>();
        collectDerivations(autoArgs, value, drvs, true);
        List<Object> out = new ArrayList<>();
        List<String> drvPaths = new ArrayList<>();
        for (NixAttrs d : drvs) {
            String drvPath = string(d.get("drvPath"));
            Object outputName = d.get("outputName");
            String output = outputName == null ? "out" : string(outputName);
            drvPaths.add(drvPath);
            out.add(output.equals("out") ? drvPath : drvPath + "!" + output);
        }
        if (write) {
            NixContext ctx = NixContext.get(null);
            try {
                Set<String> done = new HashSet<>();
                int added = 0;
                for (String p : drvPaths) added += ctx.store.writeClosure(ctx.store.daemon(), p, done);
                done.forEach(ctx.store::markValid);
                ctx.printErr("added " + added + " new paths to the store (" + done.size() + " checked)");
            } catch (IOException e) {
                throw error("cannot write to the store: " + e.getMessage());
            }
        }
        return new NixList(out.toArray());
    }

    private static void collectDerivations(NixAttrs autoArgs, Object value, List<NixAttrs> drvs, boolean top) {
        Object v = top ? autoCall(autoArgs, value) : force(value);
        if (v instanceof NixAttrs a && nixtruffle.runtime.Derivations.isDerivation(a)) {
            drvs.add(a);
        } else if (v instanceof NixAttrs a) {
            if (!top) {
                Object recurse = a.get("recurseForDerivations");
                if (recurse == null || !bool(recurse)) return;
            }
            for (int i = 0; i < a.size(); i++) collectDerivations(autoArgs, a.forceAt(i), drvs, false);
        } else if (v instanceof NixList l) {
            for (int i = 0; i < l.size(); i++) collectDerivations(autoArgs, l.forceAt(i), drvs, false);
        } else if (top) {
            throw error("expression does not evaluate to a derivation (or a set or list of those)");
        }
    }
}
