package nixtruffle.builtins;

import nixtruffle.NixContext;
import nixtruffle.Settings;
import nixtruffle.fetch.Attrs;
import nixtruffle.fetch.Fetcher;
import nixtruffle.fetch.FlakeRef;
import nixtruffle.fetch.Input;
import nixtruffle.fetch.LockFile;
import nixtruffle.fetch.Registry;
import nixtruffle.fs.Fs;
import nixtruffle.runtime.Apply;
import nixtruffle.runtime.Bytes;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.NixString;
import nixtruffle.runtime.ValuePrinter;
import nixtruffle.runtime.Values;
import nixtruffle.store.StorePaths;
import nixtruffle.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static nixtruffle.runtime.Thunk.force;

/**
 * {@code nix eval} ({@code src/nix/eval.cc} and the installables of {@code libcmd}): evaluates an
 * installable (a flake reference with an attribute path, or an attribute path in {@code --file}
 * or {@code --expr}) and prints it like CppNix. The launcher parses the command line into a JSON
 * object (see {@code launcher/EvalCommand.java}).
 */
final class CliEval {
    private CliEval() {}

    /** A command line error; the launcher adds a hint to {@code --help}. */
    static NixException usage(String message) {
        return NixException.error("\0usage\0" + message, null);
    }

    private static NixException error(String message) {
        return NixException.error(message, null);
    }

    private static String str(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        return v == null || v == Json.NULL ? null : (String) v;
    }

    /** Runs {@code nix eval}; the result is what goes to stdout. */
    static String run(Map<String, Object> cfg) {
        NixContext ctx = NixContext.get(null);
        if (!ctx.settings.isEnabled("nix-command")) {
            throw error("experimental Nix feature 'nix-command' is disabled; add '--extra-experimental-features nix-command' to enable it");
        }
        String cwd = str(cfg, "cwd");
        NixAttrs autoArgs = autoArgs(Json.arr(cfg.get("autoArgs")), cwd);
        Object v = toValue(cfg, autoArgs, cwd);

        String apply = str(cfg, "apply");
        if (apply != null) v = force(Apply.apply(parse(apply, cwd), v, null));

        Set<String> context = new TreeSet<>();
        String output;
        String writeTo = str(cfg, "writeTo");
        if (writeTo != null) {
            writeTo(v, writeTo, context);
            output = "";
        } else {
            output = switch (str(cfg, "output")) {
                case "raw" -> Values.coerce(v, false, true, context, null);
                case "json" -> {
                    String json = Json.writeCompactAsNlohmann(nixtruffle.builtins.Json.toJSON(v, context, false), Boolean.TRUE.equals(cfg.get("pretty")));
                    yield json + "\n";
                }
                default -> ValuePrinter.print(v, context) + "\n";
            };
        }
        // Derivations and files the output refers to exist afterwards, as with CppNix.
        for (String c : context) {
            String path = c.startsWith("=") ? c.substring(1) : c.startsWith("!") ? c.substring(c.indexOf('!', 1) + 1) : c;
            try {
                ctx.store.ensureWritten(path);
            } catch (IOException e) {
                throw error("cannot add '" + path + "' to the store: " + e.getMessage());
            }
        }
        return output;
    }

    /** Parses an expression given on the command line, relative to the current directory. */
    private static Object parse(String text, String cwd) {
        NixContext ctx = NixContext.get(null);
        return ctx.language.parse(text, Bytes.fromJava("«string»"), null, cwd, null).call();
    }

    // -------------------------------------------------------------- args

    /** {@code --arg}, {@code --argstr}, {@code --arg-from-file}, {@code --arg-from-stdin}. */
    private static NixAttrs autoArgs(List<Object> args, String cwd) {
        TreeMap<String, Object> m = new TreeMap<>();
        for (Object o : args) {
            Map<String, Object> a = Json.obj(o);
            String name = str(a, "name");
            String value = str(a, "value");
            m.put(name, switch (str(a, "kind")) {
                case "expr" -> Apply.lazy(new nixtruffle.runtime.Builtin("--arg", 1, x -> force(parse(value, cwd))), nixtruffle.runtime.NixNull.INSTANCE);
                case "file" -> {
                    try {
                        yield Bytes.of(Fs.readFile(value.startsWith("/") ? value : cwd + "/" + value));
                    } catch (IOException e) {
                        throw error(e.getMessage());
                    }
                }
                default -> value;
            });
        }
        return NixAttrs.fromMap(m);
    }

    // ------------------------------------------------------- installables

    private static final Pattern OUTPUTS_SPEC = Pattern.compile("\\*|[a-zA-Z0-9_+\\-.?=]+(,[a-zA-Z0-9_+\\-.?=]+)*");

    private static Object toValue(Map<String, Object> cfg, NixAttrs autoArgs, String cwd) {
        String installable = str(cfg, "installable");
        // `^out` and the like select outputs, which evaluation doesn't care about.
        int caret = installable.lastIndexOf('^');
        boolean explicitOutputs = false;
        if (caret >= 0) {
            if (!OUTPUTS_SPEC.matcher(installable.substring(caret + 1)).matches()) {
                throw error("invalid extended outputs specifier '" + installable + "'");
            }
            installable = installable.substring(0, caret);
            explicitOutputs = true;
        }
        String file = str(cfg, "file");
        String expr = str(cfg, "expr");
        if (file != null || expr != null) {
            Object root = file != null ? evalFile(file, cwd) : parse(expr, cwd);
            String attrPath = installable.equals(".") ? "" : installable;
            return force(findAlongAttrPath(attrPath, autoArgs, root));
        }
        if (installable.contains("/") && !explicitOutputs && isStorePathInstallable(installable, cwd)) {
            throw usage("installable '" + installable + "' does not correspond to a Nix language value");
        }
        Fetcher f = FetchBuiltins.fetcher();
        String s = installable;
        FlakeRef.WithFragment ref = FetchBuiltins.run(() -> FlakeRef.parseWithFragment(f, s, cwd, false, true, false));
        if (autoArgs.size() > 0) throw usage("'--arg' and '--argstr' are incompatible with flakes");
        FlakeBuiltins.LockFlags flags = lockFlags(Json.obj(cfg.get("lockFlags")), f, cwd);
        Object flake = FlakeBuiltins.callFlake(FetchBuiltins.run(() -> FlakeBuiltins.lockFlake(f, ref.ref(), flags)));
        Object outputs = Values.attrs(flake).get("outputs");

        String system = Settings.currentSystem(NixContext.get(null).settings);
        String fragment = ref.fragment();
        List<String> attrPaths = new ArrayList<>();
        if (fragment.isEmpty()) {
            attrPaths.add("packages." + system + ".default");
            attrPaths.add("defaultPackage." + system);
        } else if (fragment.startsWith(".")) {
            attrPaths.add(fragment.substring(1));
        } else {
            attrPaths.add("packages." + system + "." + fragment);
            attrPaths.add("legacyPackages." + system + "." + fragment);
            attrPaths.add(fragment);
        }
        // Like CppNix, every candidate is looked up, and the first one that exists wins.
        Object found = null;
        for (String attrPath : attrPaths) {
            Object v = lookUp(outputs, attrPath);
            if (found == null) found = v;
        }
        if (found == null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < attrPaths.size(); i++) {
                if (i > 0) sb.append(i + 1 == attrPaths.size() ? " or " : ", ");
                sb.append('\'').append(attrPaths.get(i)).append('\'');
            }
            throw error("flake '" + FetchBuiltins.run(ref.ref()::toString) + "' does not provide attribute " + sb);
        }
        return force(found);
    }

    /** CppNix's {@code followLinksToStorePath}: whether an installable with a '/' is a store path. */
    private static boolean isStorePathInstallable(String s, String cwd) {
        String path = nixtruffle.runtime.NixPath.canonicalize(s.startsWith("/") ? s : cwd + "/" + s);
        try {
            String resolved = FileBuiltins.resolveSymlinks(path, true);
            return StorePaths.toStorePath(resolved) != null || StorePaths.toStorePath(path) != null;
        } catch (NixException e) {
            return false;
        }
    }

    /** {@code lookupFileArg} and {@code evalFile}: a file, URL, {@code flake:} reference or {@code <path>}. */
    private static Object evalFile(String file, String cwd) {
        NixContext ctx = NixContext.get(null);
        if (file.equals("-")) {
            try {
                return ctx.language.parse(Bytes.of(System.in.readAllBytes()), Bytes.fromJava("«stdin»"), null, cwd, null).call();
            } catch (IOException e) {
                throw error(e.getMessage());
            }
        }
        String java = Bytes.toJava(file);
        String path;
        if (Settings.isPseudoUrl(java)) {
            path = FetchBuiltins.downloadTarballToStore(Bytes.fromJava(Settings.resolvePseudoUrl(java)));
        } else if (file.startsWith("flake:")) {
            path = FlakeBuiltins.resolveLookupPathFlake(file.substring("flake:".length()));
        } else if (file.length() > 2 && file.startsWith("<") && file.endsWith(">")) {
            Object nixPath = ctx.global("__nixPath");
            return FileBuiltins.importFile(Values.coerce(Builtins.call(ctx.global("__findFile"), nixPath, file.substring(1, file.length() - 1)),
                    false, false, new TreeSet<>(), null));
        } else {
            path = nixtruffle.runtime.NixPath.canonicalize(file.startsWith("/") ? file : cwd + "/" + file);
        }
        return FileBuiltins.importFile(path);
    }

    /** CppNix's {@code parseAttrPath}: dots separate names, which may be quoted. */
    static List<String> parseAttrPath(String s) {
        List<String> res = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                res.add(cur.toString());
                cur.setLength(0);
            } else if (c == '"') {
                i++;
                while (true) {
                    if (i == s.length()) throw error("missing closing quote in selection path '" + s + "'");
                    if (s.charAt(i) == '"') break;
                    cur.append(s.charAt(i++));
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) res.add(cur.toString());
        return res;
    }

    /** A flake output attribute, like the evaluation cache's cursors: null if missing, or not in a set. */
    private static Object lookUp(Object root, String attrPath) {
        Object v = root;
        for (String name : parseAttrPath(attrPath)) {
            if (!(force(v) instanceof NixAttrs a)) return null;
            v = a.getRaw(name);
            if (v == null) return null;
        }
        return v;
    }

    /** {@code findAlongAttrPath}: names and list indices, calling functions with the auto args on the way. */
    static Object findAlongAttrPath(String attrPath, NixAttrs autoArgs, Object root) {
        Object v = root;
        for (String attr : parseAttrPath(attrPath)) {
            Long index = attr.matches("[0-9]+") && attr.length() <= 10 && Long.parseLong(attr) <= 0xffffffffL ? Long.parseLong(attr) : null;
            v = force(Internals.autoCall(autoArgs, v));
            if (index == null) {
                if (!(v instanceof NixAttrs a)) {
                    throw error("the expression selected by the selection path '" + attrPath + "' should be a set but is " + Values.typeName(v));
                }
                if (attr.isEmpty()) throw error("empty attribute name in selection path '" + attrPath + "'");
                Object next = a.getRaw(attr);
                if (next == null) throw error("attribute '" + attr + "' in selection path '" + attrPath + "' not found");
                v = next;
            } else {
                if (!(v instanceof NixList l)) {
                    throw error("the expression selected by the selection path '" + attrPath + "' should be a list but is " + Values.typeName(v));
                }
                if (index >= l.size()) throw error("list index " + index + " in selection path '" + attrPath + "' is out of range");
                v = l.items[(int) (long) index];
            }
        }
        return v;
    }

    // ------------------------------------------------------- lock flags

    private static FlakeBuiltins.LockFlags lockFlags(Map<String, Object> j, Fetcher f, String cwd) {
        NixContext ctx = NixContext.get(null);
        FlakeBuiltins.LockFlags flags = new FlakeBuiltins.LockFlags();
        flags.updateLockFile = !Boolean.FALSE.equals(j.get("updateLockFile"));
        flags.writeLockFile = !Boolean.FALSE.equals(j.get("writeLockFile"));
        flags.recreateLockFile = Boolean.TRUE.equals(j.get("recreateLockFile"));
        flags.commitLockFile = Boolean.TRUE.equals(j.get("commitLockFile"));
        flags.useRegistries = j.get("useRegistries") instanceof Boolean b ? b : ctx.settings.getBool("use-registries");
        flags.allowUnlocked = true;
        flags.referenceLockFile = str(j, "referenceLockFile");
        flags.outputLockFile = str(j, "outputLockFile");
        FetchBuiltins.run(() -> {
            for (Object o : Json.arr(j.get("overrideFlake"))) {
                List<Object> p = Json.arr(o);
                FlakeRef from = FlakeRef.parse(f, (String) p.get(0), cwd, false, true, false);
                FlakeRef to = FlakeRef.parse(f, (String) p.get(1), cwd, false, true, false);
                Attrs extra = new Attrs();
                if (!to.subdir.isEmpty()) extra.put("dir", to.subdir);
                Registry.override(f, from.input, to.input, extra);
            }
            for (Object o : Json.arr(j.get("inputsFrom"))) {
                FlakeBuiltins.LockFlags noWrite = new FlakeBuiltins.LockFlags();
                noWrite.writeLockFile = false;
                noWrite.updateLockFile = true;
                noWrite.useRegistries = flags.useRegistries;
                var locked = FlakeBuiltins.lockFlake(f, FlakeRef.parse(f, (String) o, cwd, false, true, false), noWrite);
                for (String name : locked.lockFile().root.inputs.keySet()) {
                    if (locked.lockFile().findInput(List.of(name)) instanceof LockFile.Locked node) {
                        Attrs extra = new Attrs();
                        if (!node.lockedRef.subdir.isEmpty()) extra.put("dir", node.lockedRef.subdir);
                        Registry.override(f, Input.fromAttrs(f, Attrs.of("type", "indirect", "id", name)), node.lockedRef.input, extra);
                    }
                }
            }
            for (Object o : Json.arr(j.get("overrideInputs"))) {
                List<Object> p = Json.arr(o);
                List<String> path = LockFile.parseInputAttrPath((String) p.get(0));
                if (path.isEmpty()) {
                    throw usage("--override-input was passed a zero-length input path, which would refer to the flake itself, not an input");
                }
                flags.inputOverrides.put(path, FlakeRef.parse(f, (String) p.get(1), cwd, true, true, false));
            }
            for (Object o : Json.arr(j.get("updateInputs"))) {
                List<String> path = LockFile.parseInputAttrPath((String) o);
                if (path.isEmpty()) {
                    throw usage("--update-input was passed a zero-length input path, which would refer to the flake itself, not an input");
                }
                flags.inputUpdates.add(path);
            }
            return null;
        });
        return flags;
    }

    // ---------------------------------------------------------- write-to

    /** {@code --write-to}: a string becomes a file, a set a directory of them. */
    private static void writeTo(Object value, String path, Set<String> context) {
        try {
            if (Fs.maybeLstat(path) != null) throw error("path '" + path + "' already exists");
            write(value, path, context);
        } catch (IOException e) {
            throw error(e.getMessage());
        }
    }

    private static void write(Object value, String path, Set<String> context) throws IOException {
        Object v = force(value);
        if (NixString.is(v)) {
            NixString.addContext(v, context);
            Fs.writeFile(path, Bytes.get(NixString.value(v)), 0666);
        } else if (v instanceof NixAttrs a) {
            Fs.mkdir(path, 0777);
            for (int i = 0; i < a.size(); i++) {
                String name = a.keys[i];
                if (name.equals(".") || name.equals("..")) throw error("invalid file name '" + name + "'");
                write(a.values[i], path + "/" + name, context);
            }
        } else {
            throw error("value is not a string or an attribute set");
        }
    }
}
