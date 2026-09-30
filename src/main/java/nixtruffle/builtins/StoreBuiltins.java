package nixtruffle.builtins;

import nixtruffle.NixContext;
import nixtruffle.fs.Fs;
import nixtruffle.runtime.Apply;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.NixNull;
import nixtruffle.runtime.NixString;
import nixtruffle.runtime.Values;
import nixtruffle.store.Derivation;
import nixtruffle.store.Hash;
import nixtruffle.store.Nar;
import nixtruffle.store.Store;
import nixtruffle.store.StorePaths;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static nixtruffle.runtime.Thunk.force;
import static nixtruffle.runtime.Values.attrs;
import static nixtruffle.runtime.Values.bool;
import static nixtruffle.runtime.Values.list;
import static nixtruffle.runtime.Values.string;
import static nixtruffle.runtime.Values.stringNoCtx;

/** Derivations, string context and adding things to the store ({@code primops.cc}, {@code context.cc}). */
final class StoreBuiltins {
    private StoreBuiltins() {}

    private static NixException error(String message) {
        return NixException.error(message, null);
    }

    static Store store() {
        return NixContext.get(null).store;
    }

    static void install() {
        Builtins.def("derivationStrict", 1, a -> derivationStrict(attrs(a[0])));
        Builtins.def("placeholder", 1, a -> StorePaths.placeholder(stringNoCtx(a[0])));

        Builtins.def("toFile", 2, a -> {
            String name = stringNoCtx(a[0]);
            Set<String> context = new TreeSet<>();
            String contents = string(a[1], context);
            TreeSet<String> refs = new TreeSet<>();
            for (String c : context) {
                if (c.startsWith("=") || c.startsWith("!")) {
                    throw error("files created by builtins.toFile may not reference derivations, but " + name + " references " + contextDisplay(c));
                }
                refs.add(c);
            }
            checkName(name);
            String path = store().addText(name, contents, refs);
            NixContext.get(null).allowPath(path);
            return NixString.make(path, Set.of(path));
        });
        Builtins.def("path", 1, a -> {
            NixAttrs args = attrs(a[0]);
            String path = null;
            String name = "";
            Object filter = null;
            boolean recursive = true;
            Hash expected = null;
            Set<String> context = new TreeSet<>();
            for (int i = 0; i < args.size(); i++) {
                Object v = args.values[i];
                switch (args.keys[i]) {
                    case "path" -> path = Values.coerceToPath(v, context, null);
                    case "name" -> name = stringNoCtx(v);
                    case "filter" -> filter = Values.function(v);
                    case "recursive" -> recursive = bool(v);
                    case "sha256" -> {
                        try {
                            expected = Hash.parseAllowEmpty(stringNoCtx(v), "sha256");
                        } catch (IllegalArgumentException e) {
                            throw error(e.getMessage());
                        }
                    }
                    default -> throw error("unsupported argument '" + args.keys[i] + "' to 'builtins.path'");
                }
            }
            if (path == null) throw error("missing required 'path' attribute in the first argument to 'builtins.path'");
            if (name.isEmpty()) name = baseName(path);
            return addPath(path, name, filter, recursive, expected, context);
        });
        Builtins.def("filterSource", 2, a -> {
            Set<String> context = new TreeSet<>();
            String path = Values.coerceToPath(a[1], context, null);
            Object filter = Values.function(a[0]);
            return addPath(path, baseName(path), filter, true, null, context);
        });
        Builtins.def("storePath", 1, a -> {
            if (NixContext.get(null).settings.getBool("pure-eval")) throw error("'builtins.storePath' is not allowed in pure evaluation mode");
            Set<String> context = new TreeSet<>();
            String path = Values.coerceToPath(a[0], context, null);
            if (StorePaths.parseStorePath(path) == null) path = FileBuiltins.resolveSymlinks(path, true);
            String storePath = StorePaths.toStorePath(path);
            if (storePath == null) throw error("path '" + path + "' is not in the Nix store");
            if (!store().readOnly) ensurePath(storePath);
            context.add(storePath);
            return NixString.make(path, context);
        });

        // --------------------------------------------------------- context
        Builtins.def("unsafeDiscardStringContext", 1, a -> Values.coerce(a[0], false, true, new TreeSet<>(), null));
        Builtins.def("hasContext", 1, a -> {
            Object s = force(a[0]);
            string(s);
            return s instanceof NixString;
        });
        Builtins.def("unsafeDiscardOutputDependency", 1, a -> {
            Set<String> context = new TreeSet<>();
            String s = Values.coerce(a[0], false, true, context, null);
            Set<String> out = new TreeSet<>();
            for (String c : context) out.add(c.startsWith("=") ? c.substring(1) : c);
            return NixString.make(s, out);
        });
        Builtins.def("addDrvOutputDependencies", 1, a -> {
            Set<String> context = new TreeSet<>();
            String s = Values.coerce(a[0], false, true, context, null);
            if (context.size() != 1) throw error("context of string '" + s + "' must have exactly one element, but has " + context.size());
            String c = context.iterator().next();
            if (c.startsWith("!")) throw error("`addDrvOutputDependencies` can only act on derivations, not on a derivation output such as '" + c.substring(1, c.indexOf('!', 1)) + "'");
            if (!c.startsWith("=")) {
                if (!c.endsWith(".drv")) throw error("path '" + c + "' is not a derivation");
                c = "=" + c;
            }
            return NixString.make(s, Set.of(c));
        });
        Builtins.def("getContext", 1, a -> {
            Set<String> context = new TreeSet<>();
            string(a[0], context);
            TreeMap<String, TreeMap<String, Object>> info = new TreeMap<>();
            TreeMap<String, List<Object>> outputs = new TreeMap<>();
            for (String c : context) {
                if (c.startsWith("=")) {
                    info.computeIfAbsent(c.substring(1), k -> new TreeMap<>()).put("allOutputs", true);
                } else if (c.startsWith("!")) {
                    int bang = c.indexOf('!', 1);
                    String drv = c.substring(bang + 1);
                    info.computeIfAbsent(drv, k -> new TreeMap<>());
                    outputs.computeIfAbsent(drv, k -> new ArrayList<>()).add(c.substring(1, bang));
                } else {
                    info.computeIfAbsent(c, k -> new TreeMap<>()).put("path", true);
                }
            }
            TreeMap<String, Object> result = new TreeMap<>();
            for (Map.Entry<String, TreeMap<String, Object>> e : info.entrySet()) {
                List<Object> outs = outputs.get(e.getKey());
                if (outs != null) e.getValue().put("outputs", new NixList(outs.toArray()));
                result.put(e.getKey(), NixAttrs.fromMap(e.getValue()));
            }
            return NixAttrs.fromMap(result);
        });
        Builtins.def("appendContext", 2, a -> {
            Set<String> context = new TreeSet<>();
            String s = string(a[0], context);
            NixAttrs extra = attrs(a[1]);
            for (int i = 0; i < extra.size(); i++) {
                String name = extra.keys[i];
                String path = StorePaths.parseStorePath(name);
                if (path == null) throw error("context key '" + name + "' is not a store path");
                if (!store().readOnly) ensurePath(path);
                NixAttrs info = attrs(extra.values[i]);
                Object p = info.getRaw("path");
                if (p != null && bool(p)) context.add(path);
                Object all = info.getRaw("allOutputs");
                if (all != null && bool(all)) {
                    if (!name.endsWith(".drv")) throw error("tried to add all-outputs context of " + name + ", which is not a derivation, to a string");
                    context.add("=" + path);
                }
                Object outs = info.getRaw("outputs");
                if (outs != null) {
                    Object[] names = list(outs);
                    if (names.length > 0 && !name.endsWith(".drv")) throw error("tried to add derivation output context of " + name + ", which is not a derivation, to a string");
                    for (Object o : names) context.add("!" + stringNoCtx(o) + "!" + path);
                }
            }
            return NixString.make(s, context);
        });
    }

    /** How Nix shows a context element in messages. */
    static String contextDisplay(String c) {
        if (c.startsWith("=")) return c.substring(1);
        if (c.startsWith("!")) {
            int bang = c.indexOf('!', 1);
            return c.substring(bang + 1) + "^" + c.substring(1, bang);
        }
        return c;
    }

    /** {@code Store::ensurePath}: the path must exist (we can't substitute). */
    static void ensurePath(String storePath) {
        try {
            if (!store().isValidPath(storePath)) throw error("path '" + storePath + "' does not exist and cannot be created");
        } catch (IOException e) {
            throw error("cannot query the Nix store: " + e.getMessage());
        }
    }

    static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    static void checkName(String name) {
        String e = StorePaths.checkName(name);
        if (e != null) throw error(e);
    }

    /** {@code builtins.path} / {@code filterSource} (CppNix's {@code addPath}). */
    private static Object addPath(String path, String name, Object filter, boolean recursive, Hash expected, Set<String> context) {
        try {
            if (!context.isEmpty() && StorePaths.toStorePath(path) != null) FileBuiltins.realiseContext(context);
            checkName(name);
            NixContext ctx = NixContext.get(null);
            if (expected != null) {
                String expectedPath = StorePaths.fixedOutputPath(recursive, expected, name);
                if (store().isValidPath(expectedPath)) {
                    ctx.allowPath(expectedPath);
                    return NixString.make(expectedPath, Set.of(expectedPath));
                }
            }
            ctx.checkAccess(path);
            FileBuiltins.ensureReadable(path);
            String resolved = FileBuiltins.resolveSymlinks(path, false);
            ctx.checkAccess(resolved);
            Nar.Filter narFilter = filter == null ? null : (p, type) -> bool(Builtins.call(filter, p, type));
            if (!recursive) {
                Fs.Stat st = Fs.stat(resolved);
                if (!st.isRegular()) throw error("file '" + path + "' is not a regular file, and can only be added with 'recursive = true'");
            }
            String storePath = store().addSource(name, resolved, narFilter, recursive);
            if (expected != null && !storePath.equals(StorePaths.fixedOutputPath(recursive, expected, name))) {
                throw error("store path mismatch in (possibly filtered) path added from '" + path + "'");
            }
            ctx.allowPath(storePath);
            return NixString.make(storePath, Set.of(storePath));
        } catch (IOException e) {
            throw error("while adding path '" + path + "': " + e.getMessage());
        }
    }

    // -------------------------------------------------------- derivations

    /** Port of CppNix's {@code derivationStrict}. */
    private static Object derivationStrict(NixAttrs attrs) {
        Object nameValue = attrs.getRaw("name");
        if (nameValue == null) throw error("attribute 'name' missing");
        String drvName = stringNoCtx(nameValue);
        try {
            return derivationStrict(attrs, drvName, store());
        } catch (NixException e) {
            throw e instanceof NixException.Catchable ? e
                    : NixException.error(e.getMessage() + "\n       … while evaluating derivation '" + drvName + "'", null);
        }
    }

    private static String handleHashMode(String s) {
        return switch (s) {
            case "recursive", "nar" -> "nar";
            case "flat" -> "flat";
            case "text" -> throw error("experimental Nix feature 'dynamic-derivations' is disabled; add '--extra-experimental-features dynamic-derivations' to enable it");
            case "git" -> throw error("experimental Nix feature 'git-hashing' is disabled; add '--extra-experimental-features git-hashing' to enable it");
            default -> throw error("invalid value '" + s + "' for 'outputHashMode' attribute");
        };
    }

    private static TreeSet<String> handleOutputs(List<String> names) {
        TreeSet<String> outputs = new TreeSet<>();
        for (String n : names) {
            if (outputs.contains(n)) throw error("duplicate derivation output '" + n + "'");
            if (n.equals("drvPath")) throw error("invalid derivation output name 'drvPath'");
            outputs.add(n);
        }
        if (outputs.isEmpty()) throw error("derivation cannot have an empty set of outputs");
        return outputs;
    }

    /** Hash algorithm names that {@code outputHashAlgo} understands (others mean "none"). */
    private static String parseHashAlgoOpt(String s) {
        if (s.equals("blake3")) throw error("experimental Nix feature 'blake3-hashes' is disabled; add '--extra-experimental-features blake3-hashes' to enable it");
        return Builtins.parseHashAlgoOpt(s);
    }

    private static Object derivationStrict(NixAttrs attrs, String drvName, Store store) {
        String nameError = StorePaths.checkName(drvName);
        if (nameError != null) throw error("invalid derivation name: " + nameError + ". Please pass a different 'name'.");
        Object sa = attrs.getRaw("__structuredAttrs");
        TreeMap<String, String> json = sa != null && bool(sa) ? new TreeMap<>() : null;
        Object in = attrs.getRaw("__ignoreNulls");
        boolean ignoreNulls = in != null && bool(in);

        Derivation drv = new Derivation(drvName);
        Set<String> context = new TreeSet<>();
        TreeSet<String> outputs = new TreeSet<>(List.of("out"));
        String outputHash = null;
        String outputHashAlgo = null;
        String hashMode = null;

        for (int i = 0; i < attrs.size(); i++) {
            String key = attrs.keys[i];
            if (key.equals("__ignoreNulls")) continue;
            try {
                if (ignoreNulls && attrs.forceAt(i) instanceof NixNull) continue;
                Object value = attrs.values[i];
                switch (key) {
                    case "__contentAddressed" -> {
                        if (bool(value)) throw error("experimental Nix feature 'ca-derivations' is disabled; add '--extra-experimental-features ca-derivations' to enable it");
                    }
                    case "__impure" -> {
                        if (bool(value)) throw error("experimental Nix feature 'impure-derivations' is disabled; add '--extra-experimental-features impure-derivations' to enable it");
                    }
                    case "args" -> {
                        for (Object arg : list(value)) drv.args.add(Values.coerce(arg, true, true, context, null));
                    }
                    default -> {
                        if (json != null) {
                            if (key.equals("__structuredAttrs")) continue;
                            json.put(key, Json.toJSON(value, context, true));
                            switch (key) {
                                case "builder" -> drv.builder = string(value, context);
                                case "system" -> drv.platform = stringNoCtx(value);
                                case "outputHash" -> outputHash = stringNoCtx(value);
                                case "outputHashAlgo" -> outputHashAlgo = parseHashAlgoOpt(stringNoCtx(value));
                                case "outputHashMode" -> hashMode = handleHashMode(stringNoCtx(value));
                                case "outputs" -> {
                                    List<String> names = new ArrayList<>();
                                    for (Object o : list(value)) names.add(stringNoCtx(o));
                                    outputs = handleOutputs(names);
                                }
                                default -> {}
                            }
                        } else {
                            String s = Values.coerce(value, true, true, context, null);
                            drv.env.put(key, s);
                            switch (key) {
                                case "builder" -> drv.builder = s;
                                case "system" -> drv.platform = s;
                                case "outputHash" -> outputHash = s;
                                case "outputHashAlgo" -> outputHashAlgo = parseHashAlgoOpt(s);
                                case "outputHashMode" -> hashMode = handleHashMode(s);
                                case "outputs" -> outputs = handleOutputs(tokenize(s));
                                default -> {}
                            }
                        }
                    }
                }
            } catch (NixException e) {
                throw e instanceof NixException.Catchable ? e
                        : NixException.error(e.getMessage() + "\n       … while evaluating attribute '" + key + "' of derivation '" + drvName + "'", null);
            }
        }
        if (json != null) drv.env.put("__json", Json.object(json));

        // Everything referenced from the attributes' string context becomes an input.
        for (String c : context) {
            if (c.startsWith("=")) {
                for (String p : store.closure(c.substring(1))) {
                    drv.inputSrcs.add(p);
                    if (p.endsWith(".drv")) drv.inputDrvs.put(p, new TreeSet<>(store.derivation(p).outputs.keySet()));
                }
            } else if (c.startsWith("!")) {
                int bang = c.indexOf('!', 1);
                drv.inputDrvs.computeIfAbsent(c.substring(bang + 1), k -> new TreeSet<>()).add(c.substring(1, bang));
            } else {
                drv.inputSrcs.add(c);
            }
        }

        if (drv.builder.isEmpty()) throw error("required attribute 'builder' missing");
        if (drv.platform.isEmpty()) throw error("required attribute 'system' missing");
        if (drvName.endsWith(".drv")) throw error("derivation names are allowed to end in '.drv' only if they produce a single derivation file");

        if (outputHash != null) {
            if (outputs.size() != 1 || !outputs.contains("out")) throw error("multiple outputs are not supported in fixed-output derivations");
            Hash h;
            try {
                h = Hash.parseAllowEmpty(outputHash, outputHashAlgo);
            } catch (IllegalArgumentException e) {
                throw error(e.getMessage());
            }
            if (outputHash.isEmpty()) NixContext.get(null).printErr("warning: found empty hash, assuming '" + h.sri() + "'");
            boolean recursive = "nar".equals(hashMode);
            String outPath = StorePaths.fixedOutputPath(recursive, h, drvName);
            drv.env.put("out", outPath);
            drv.outputs.put("out", new Derivation.Output(outPath, (recursive ? "r:" : "") + h.algo(), h.hex()));
        } else {
            // Hash the derivation with blank output paths, then fill them in.
            for (String o : outputs) {
                drv.env.put(o, "");
                drv.outputs.put(o, Derivation.Output.DEFERRED);
            }
            Map<String, String> hashes = null;
            for (String o : outputs) {
                String outName = o.equals("out") ? drvName : drvName + "-" + o;
                String bad = StorePaths.checkName(outName);
                if (bad != null) throw error("invalid derivation output '" + o + "': " + bad);
                if (hashes == null) hashes = store.hashModulo(drv, true);
                String path = StorePaths.outputPath(o, hashes.get(o), drvName);
                drv.env.put(o, path);
                drv.outputs.put(o, new Derivation.Output(path, "", ""));
            }
        }
        checkName(drvName + ".drv");

        String drvPath = store.addDerivation(drv);
        TreeMap<String, Object> result = new TreeMap<>();
        result.put("drvPath", NixString.make(drvPath, Set.of("=" + drvPath)));
        for (Map.Entry<String, Derivation.Output> o : drv.outputs.entrySet()) {
            result.put(o.getKey(), NixString.make(o.getValue().path(), Set.of("!" + o.getKey() + "!" + drvPath)));
        }
        return NixAttrs.fromMap(result);
    }

    /** {@code tokenizeString}: split at spaces, tabs and newlines, dropping empty tokens. */
    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split("[ \t\n\r]+")) if (!t.isEmpty()) out.add(t);
        return out;
    }
}
