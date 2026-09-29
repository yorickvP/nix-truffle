package nixtruffle.builtins;

import com.oracle.truffle.api.TruffleFile;
import nixtruffle.NixContext;
import nixtruffle.runtime.Apply;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.NixNull;
import nixtruffle.runtime.NixPath;
import nixtruffle.runtime.NixString;
import nixtruffle.runtime.Thunk;
import nixtruffle.runtime.Values;
import nixtruffle.store.Derivation;
import nixtruffle.store.Hash;
import nixtruffle.store.Nar;
import nixtruffle.store.Store;
import nixtruffle.store.StorePaths;

import java.io.IOException;
import java.util.ArrayList;
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

/** Derivations, string context and adding things to the store. */
final class StoreBuiltins {
    private StoreBuiltins() {}

    private static NixException error(String message) {
        return NixException.error(message, null);
    }

    private static Store store() {
        return NixContext.get(null).store;
    }

    static void install() {
        Builtins.def("derivationStrict", 1, a -> derivationStrict(attrs(a[0])));
        Builtins.def("derivation", 1, a -> Apply.apply(NixContext.get(null).derivationLambda(), a[0], null));
        Builtins.def("placeholder", 1, a -> StorePaths.placeholder(string(a[0])));

        Builtins.def("toFile", 2, a -> {
            String name = string(a[0]);
            Set<String> context = new TreeSet<>();
            String contents = string(a[1], context);
            TreeSet<String> refs = new TreeSet<>();
            for (String c : context) {
                if (c.startsWith("=") || c.startsWith("!")) {
                    throw error("files created by builtins.toFile may not reference derivations, but " + name + " references " + c);
                }
                refs.add(c);
            }
            checkName(name);
            String path = store().addText(name, contents, refs);
            return NixString.make(path, Set.of(path));
        });
        Builtins.def("path", 1, a -> {
            NixAttrs args = attrs(a[0]);
            Object p = args.get("path");
            if (p == null) throw error("missing required 'path' attribute in the first argument to builtins.path");
            String path = Values.coerce(p, false, false, null, null);
            Object name = args.get("name");
            Object recursive = args.get("recursive");
            return addPath(path, name == null ? baseName(path) : string(name), args.get("filter"), recursive == null || bool(recursive));
        });
        Builtins.def("filterSource", 2, a -> {
            String path = Values.coerce(a[1], false, false, null, null);
            return addPath(path, baseName(path), force(a[0]), true);
        });
        Builtins.def("storePath", 1, a -> {
            Set<String> context = new TreeSet<>();
            String path = Values.coerce(a[0], false, false, context, null);
            String storePath = StorePaths.toStorePath(path);
            if (storePath == null) throw error("path '" + path + "' is not in the Nix store");
            context.add(storePath);
            return NixString.make(path, context);
        });
        Builtins.def("hashFile", 2, a -> {
            try {
                return Hash.of(string(a[0]), Builtins.file(a[1]).readAllBytes()).hex();
            } catch (IOException e) {
                throw error("cannot read file: " + e.getMessage());
            }
        });

        // --------------------------------------------------------- context
        Builtins.def("unsafeDiscardStringContext", 1, a -> string(a[0]));
        Builtins.def("hasContext", 1, a -> force(a[0]) instanceof NixString);
        Builtins.def("unsafeDiscardOutputDependency", 1, a -> {
            Set<String> context = new TreeSet<>();
            String s = string(a[0], context);
            Set<String> out = new TreeSet<>();
            for (String c : context) out.add(c.startsWith("=") ? c.substring(1) : c);
            return NixString.make(s, out);
        });
        Builtins.def("addDrvOutputDependencies", 1, a -> {
            Set<String> context = new TreeSet<>();
            String s = string(a[0], context);
            if (context.size() != 1) throw error("context of string '" + s + "' must have exactly one element, but has " + context.size());
            String c = context.iterator().next();
            if (c.startsWith("!")) throw error("`addDrvOutputDependencies` can only act on derivations, not on a derivation output such as '" + c + "'");
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
                String path = extra.keys[i];
                if (StorePaths.toStorePath(path) == null) throw error("context key '" + path + "' is not a store path");
                NixAttrs info = attrs(extra.values[i]);
                Object p = info.get("path");
                if (p != null && bool(p)) context.add(path);
                Object all = info.get("allOutputs");
                if (all != null && bool(all)) {
                    if (!path.endsWith(".drv")) throw error("tried to add all-outputs context of " + path + ", which is not a derivation, to a string");
                    context.add("=" + path);
                }
                Object outs = info.get("outputs");
                if (outs != null) {
                    Object[] names = list(outs);
                    if (names.length > 0 && !path.endsWith(".drv")) throw error("tried to add derivation output context of " + path + ", which is not a derivation, to a string");
                    for (Object o : names) context.add("!" + string(o) + "!" + path);
                }
            }
            return NixString.make(s, context);
        });
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void checkName(String name) {
        String e = StorePaths.checkName(name);
        if (e != null) throw error(e);
    }

    /** {@code builtins.path} / {@code filterSource}. */
    private static Object addPath(String path, String name, Object filter, boolean recursive) {
        checkName(name);
        TruffleFile file = NixContext.get(null).env.getPublicTruffleFile(path);
        Nar.Filter narFilter = filter == null || filter instanceof NixNull ? null
                : (p, type) -> bool(Apply.apply(filter, p, type));
        try {
            String storePath = store().addSource(name, file.getCanonicalFile(), narFilter, recursive);
            return NixString.make(storePath, Set.of(storePath));
        } catch (IOException e) {
            throw error("cannot add '" + path + "' to the store: " + e.getMessage());
        }
    }

    // -------------------------------------------------------- derivations

    /**
     * Port of CppNix's {@code derivationStrict}: turn the attributes into a store derivation (env
     * vars or {@code __json}), collect inputs from string context, compute output paths (fixed or
     * via {@code hashDerivationModulo}), and "write" the .drv.
     */
    private static Object derivationStrict(NixAttrs attrs) {
        Store store = store();
        Object nameValue = attrs.get("name");
        if (nameValue == null) throw error("required attribute 'name' missing");
        String drvName = string(nameValue);
        try {
            return derivationStrict(attrs, drvName, store);
        } catch (NixException e) {
            throw error(e.getMessage() + "\n       … while evaluating derivation '" + drvName + "'");
        }
    }

    private static Object derivationStrict(NixAttrs attrs, String drvName, Store store) {
        checkName(drvName);
        Object sa = attrs.get("__structuredAttrs");
        TreeMap<String, String> json = sa != null && bool(sa) ? new TreeMap<>() : null;
        Object in = attrs.get("__ignoreNulls");
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
                if (key.equals("__contentAddressed") && bool(value)) {
                    throw error("content-addressed derivations are not supported");
                } else if (key.equals("__impure") && bool(value)) {
                    throw error("impure derivations are not supported");
                } else if (key.equals("args")) {
                    for (Object arg : list(value)) drv.args.add(Values.coerce(arg, true, true, context, null));
                } else if (json != null) {
                    if (key.equals("__structuredAttrs")) continue;
                    json.put(key, Json.toJSON(value, context, true));
                    switch (key) {
                        case "builder" -> drv.builder = string(value, context);
                        case "system" -> drv.platform = string(value);
                        case "outputHash" -> outputHash = string(value);
                        case "outputHashAlgo" -> outputHashAlgo = string(value);
                        case "outputHashMode" -> hashMode = string(value);
                        case "outputs" -> {
                            List<String> names = new ArrayList<>();
                            for (Object o : list(value)) names.add(string(o));
                            outputs = outputNames(names);
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
                        case "outputHashAlgo" -> outputHashAlgo = s;
                        case "outputHashMode" -> hashMode = s;
                        case "outputs" -> outputs = outputNames(List.of(s.trim().isEmpty() ? new String[0] : s.trim().split("[ \t\n\r]+")));
                        default -> {}
                    }
                }
            } catch (NixException e) {
                throw error(e.getMessage() + "\n       … while evaluating attribute '" + key + "' of derivation '" + drvName + "'");
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
        if (hashMode != null && !hashMode.equals("flat") && !hashMode.equals("recursive")) {
            throw error("invalid value '" + hashMode + "' for 'outputHashMode' attribute");
        }

        if (outputHash != null) {
            if (outputs.size() != 1 || !outputs.contains("out")) throw error("multiple outputs are not supported in fixed-output derivations");
            String algo = outputHashAlgo == null || outputHashAlgo.isEmpty() ? null : outputHashAlgo;
            Hash h;
            try {
                if (outputHash.isEmpty()) {
                    if (algo == null) throw error("empty hash requires explicit hash type");
                    h = new Hash(algo, new byte[Hash.size(algo)]);
                    NixContext.get(null).err.println("warning: found empty hash, assuming '" + h.sri() + "'");
                } else {
                    h = Hash.parse(outputHash, algo);
                }
            } catch (IllegalArgumentException e) {
                throw error(e.getMessage());
            }
            boolean recursive = "recursive".equals(hashMode);
            String outPath = StorePaths.fixedOutputPath(recursive, h, drvName);
            drv.env.put("out", outPath);
            drv.outputs.put("out", new Derivation.Output(outPath, (recursive ? "r:" : "") + h.algo(), h.hex()));
        } else {
            // Hash the derivation with blank output paths, then fill them in.
            for (String o : outputs) {
                drv.env.put(o, "");
                drv.outputs.put(o, Derivation.Output.DEFERRED);
            }
            Map<String, String> hashes = store.hashModulo(drv, true);
            for (String o : outputs) {
                checkName(o.equals("out") ? drvName : drvName + "-" + o);
                String path = StorePaths.outputPath(o, hashes.get(o), drvName);
                drv.env.put(o, path);
                drv.outputs.put(o, new Derivation.Output(path, "", ""));
            }
        }

        String drvPath = store.addDerivation(drv);
        TreeMap<String, Object> result = new TreeMap<>();
        result.put("drvPath", NixString.make(drvPath, Set.of("=" + drvPath)));
        for (Map.Entry<String, Derivation.Output> o : drv.outputs.entrySet()) {
            result.put(o.getKey(), NixString.make(o.getValue().path(), Set.of("!" + o.getKey() + "!" + drvPath)));
        }
        return NixAttrs.fromMap(result);
    }

    private static TreeSet<String> outputNames(List<String> names) {
        TreeSet<String> outputs = new TreeSet<>();
        for (String n : names) {
            if (!outputs.add(n)) throw error("duplicate derivation output '" + n + "'");
            if (n.equals("drv")) throw error("invalid derivation output name 'drv'");
        }
        if (outputs.isEmpty()) throw error("derivation cannot have an empty set of outputs");
        return outputs;
    }

    static Object forceValue(Object v) {
        return Thunk.force(v);
    }
}
