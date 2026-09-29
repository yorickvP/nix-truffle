package nixtruffle.builtins;

import nixtruffle.NixContext;
import nixtruffle.Settings;
import nixtruffle.fs.Fs;
import nixtruffle.runtime.Apply;
import nixtruffle.runtime.Bytes;
import nixtruffle.runtime.Foreign;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.NixPath;
import nixtruffle.runtime.NixString;
import nixtruffle.runtime.Values;
import nixtruffle.store.Hash;
import nixtruffle.store.StorePaths;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static nixtruffle.runtime.Thunk.force;
import static nixtruffle.runtime.Values.attrs;
import static nixtruffle.runtime.Values.list;
import static nixtruffle.runtime.Values.stringNoCtx;

/** Reading files and the lookup path: {@code import}, {@code readFile}, {@code findFile}, ... */
public final class FileBuiltins {
    private FileBuiltins() {}

    private static NixException error(String message) {
        return NixException.error(message, null);
    }

    /** How {@link #realisePath} resolves symlinks. */
    enum Symlinks { NONE, ANCESTORS, FULL }

    static void install() {
        Builtins.def("import", 1, a -> importPath(a[0], null));
        Builtins.def("scopedImport", 2, a -> importPath(a[1], a[0]));
        Builtins.def("readFile", 1, a -> {
            String path = realisePath(a[0], Symlinks.FULL);
            String s;
            try {
                s = Bytes.of(Fs.readFile(path));
            } catch (IOException e) {
                throw error(e.getMessage());
            }
            if (s.indexOf('\0') >= 0) throw error("the contents of the file '" + path + "' cannot be represented as a Nix string");
            return NixString.make(s, referencesIn(path, s));
        });
        Builtins.def("pathExists", 1, a -> {
            Object v = force(a[0]);
            boolean mustBeDir = NixString.is(v) && (NixString.value(v).endsWith("/") || NixString.value(v).endsWith("/."));
            String path = realisePath(v, mustBeDir ? Symlinks.FULL : Symlinks.ANCESTORS);
            try {
                Fs.Stat st = Fs.maybeLstat(path);
                return st != null && (!mustBeDir || st.isDirectory());
            } catch (IOException e) {
                throw error(e.getMessage());
            }
        });
        Builtins.def("readDir", 1, a -> {
            String path = realisePath(a[0], Symlinks.FULL);
            TreeMap<String, Object> map = new TreeMap<>();
            try {
                for (Fs.Entry e : Fs.readDirectory(path)) {
                    String child = (path.endsWith("/") ? path : path + "/") + e.name();
                    map.put(e.name(), switch (e.dtype()) {
                        case Fs.Entry.DT_DIR -> "directory";
                        case Fs.Entry.DT_REG -> "regular";
                        case Fs.Entry.DT_LNK -> "symlink";
                        case Fs.Entry.DT_UNKNOWN -> Apply.lazy(NixContext.get(null).global("__readFileType"), new NixPath(child));
                        default -> "unknown";
                    });
                }
            } catch (IOException e) {
                throw error(e.getMessage());
            }
            return NixAttrs.fromMap(map);
        });
        Builtins.def("readFileType", 1, a -> {
            String path = realisePath(a[0], Symlinks.NONE);
            try {
                return Fs.lstat(path).typeName();
            } catch (IOException e) {
                throw error(e.getMessage());
            }
        });
        Builtins.def("hashFile", 2, a -> {
            String algo = Builtins.hashAlgo(stringNoCtx(a[0]));
            String path = realisePath(a[1], Symlinks.FULL);
            try {
                return Hash.of(algo, Fs.readFile(path)).hex();
            } catch (IOException e) {
                throw error(e.getMessage());
            }
        });
        Builtins.def("toPath", 1, a -> {
            Set<String> context = new TreeSet<>();
            String path = Values.coerceToPath(a[0], context, null);
            return NixString.make(path, context);
        });
        Builtins.def("findFile", 2, a -> {
            Object[] entries = list(a[0]);
            List<String[]> lookupPath = new ArrayList<>();
            for (Object e : entries) {
                NixAttrs entry = attrs(e);
                Object p = entry.getRaw("prefix");
                String prefix = p == null ? "" : stringNoCtx(p);
                Set<String> context = new TreeSet<>();
                String path = Values.coerce(Builtins.required(entry, "path"), false, false, context, null);
                try {
                    realiseContext(context);
                } catch (NixException ex) {
                    throw error("cannot find '" + path + "', since path '" + context.iterator().next() + "' is not valid");
                }
                lookupPath.add(new String[] {prefix, path});
            }
            return new NixPath(findFile(lookupPath, stringNoCtx(a[1])));
        });
    }

    // --------------------------------------------------------------- paths

    /**
     * CppNix's {@code realisePath}: coerce to an absolute path, make sure the store paths its
     * context refers to exist, and resolve symlinks. Store paths this evaluation created are
     * written to the store first, so that they can be read.
     */
    static String realisePath(Object v, Symlinks mode) {
        Set<String> context = new TreeSet<>();
        String path = Values.coerceToPath(v, context, null);
        if (!context.isEmpty()) realiseContext(context);
        ensureReadable(path);
        return mode == Symlinks.NONE ? path : resolveSymlinks(path, mode == Symlinks.FULL);
    }

    /** Store paths we created are written to the real store before they are read. */
    static void ensureReadable(String path) {
        String storePath = StorePaths.toStorePath(path);
        if (storePath == null) return;
        try {
            NixContext.get(null).store.ensureWritten(storePath);
        } catch (IOException e) {
            throw error("cannot add '" + storePath + "' to the store: " + e.getMessage());
        }
    }

    /**
     * {@code realiseContext}: every path the context refers to must be valid. Derivation outputs
     * would have to be built (import from derivation), which nix-truffle doesn't do.
     */
    static void realiseContext(Set<String> context) {
        for (String c : context) {
            String path = c.startsWith("=") ? c.substring(1) : c.startsWith("!") ? c.substring(c.indexOf('!', 1) + 1) : c;
            StoreBuiltins.ensurePath(path);
            if (c.startsWith("!")) {
                throw error("cannot build '" + path + "^" + c.substring(1, c.indexOf('!', 1))
                        + "' during evaluation: import from derivation is not supported by nix-truffle");
            }
            ensureReadable(path);
        }
    }

    /** Port of {@code SourceAccessor::resolveSymlinks}; with {@code full}, the last component too. */
    public static String resolveSymlinks(String path, boolean full) {
        ArrayDeque<String> todo = new ArrayDeque<>(Arrays.asList(path.split("/")));
        List<String> res = new ArrayList<>();
        int linksAllowed = 1024;
        try {
            while (!todo.isEmpty()) {
                String c = todo.pollFirst();
                if (c.isEmpty() || c.equals(".")) continue;
                if (c.equals("..")) {
                    if (!res.isEmpty()) res.remove(res.size() - 1);
                    continue;
                }
                res.add(c);
                if (full || !todo.isEmpty()) {
                    String current = "/" + String.join("/", res);
                    Fs.Stat st = Fs.maybeLstat(current);
                    if (st != null && st.isSymlink()) {
                        if (linksAllowed-- == 0) throw error("infinite symlink recursion in path '" + path + "'");
                        String target = Fs.readLink(current);
                        res.remove(res.size() - 1);
                        if (target.startsWith("/")) res.clear();
                        String[] parts = target.split("/");
                        for (int i = parts.length - 1; i >= 0; i--) todo.addFirst(parts[i]);
                    }
                }
            }
        } catch (IOException e) {
            throw error(e.getMessage());
        }
        return "/" + String.join("/", res);
    }

    /** {@code resolveExprPath}: follow symlinks (so relative paths work), add {@code /default.nix} to directories. */
    static String resolveExprPath(String path) {
        try {
            for (int follow = 0; !path.equals("/"); follow++) {
                if (follow >= 1024) throw error("too many symbolic links encountered while traversing the path '" + path + "'");
                int slash = path.lastIndexOf('/');
                String parent = slash == 0 ? "/" : path.substring(0, slash);
                String p = resolveSymlinks(parent, true);
                p = (p.equals("/") ? "" : p) + "/" + path.substring(slash + 1);
                Fs.Stat st = Fs.lstat(p);
                if (!st.isSymlink()) break;
                String target = Fs.readLink(p);
                path = NixPath.canonicalize(target.startsWith("/") ? target : parent + "/" + target);
            }
            Fs.Stat st = Fs.maybeStat(path);
            if (st != null && st.isDirectory()) return (path.equals("/") ? "" : path) + "/default.nix";
            return path;
        } catch (IOException e) {
            throw error(e.getMessage());
        }
    }

    /** Store paths a file in the store refers to: its path's references that occur in it. */
    private static Set<String> referencesIn(String path, String contents) {
        String storePath = StorePaths.toStorePath(path);
        Set<String> out = new TreeSet<>();
        if (storePath == null) return out;
        for (String ref : NixContext.get(null).store.referencesOf(storePath)) {
            String hashPart = ref.substring(StorePaths.STORE_DIR.length() + 1, StorePaths.STORE_DIR.length() + 33);
            if (contents.contains(hashPart)) out.add(ref);
        }
        return out;
    }

    // -------------------------------------------------------------- import

    /** Evaluates a file like {@code import} (the launcher's FILE arguments). */
    static Object importFile(String path) {
        return importPath(new NixPath(NixPath.canonicalize(path)), null);
    }

    /** {@code import} and {@code scopedImport}. */
    private static Object importPath(Object pathArg, Object scope) {
        NixContext ctx = NixContext.get(null);
        String path = realisePath(pathArg, Symlinks.NONE);
        if (path.startsWith("/__corepkgs__/")) return ctx.corepkgValue(path.substring("/__corepkgs__/".length()));
        if (StorePaths.isStorePath(path) && path.endsWith(".drv") && isValid(path)) {
            return Apply.apply(ctx.corepkgValue("imported-drv-to-derivation.nix"), DrvImport.toValue(path), null);
        }
        String file = resolveExprPath(path);
        if (scope != null) {
            NixAttrs s = attrs(scope);
            return parseFile(ctx, file, new HashSet<>(Arrays.asList(s.keys))).call(s);
        }
        Object cached = ctx.importCache.get(file);
        if (cached != null) return cached;
        Object result;
        String language = ctx.settings.getBool("polyglot") ? foreignLanguage(file) : null;
        if (language != null) {
            try {
                var source = com.oracle.truffle.api.source.Source.newBuilder(language, ctx.env.getPublicTruffleFile(Bytes.toJava(file))).build();
                result = Foreign.toNix(ctx.env.parsePublic(source).call());
            } catch (IOException e) {
                throw error("cannot import '" + file + "': " + e.getMessage());
            }
        } else {
            result = parseFile(ctx, file, null).call();
        }
        ctx.importCache.put(file, result);
        return result;
    }

    private static boolean isValid(String storePath) {
        try {
            return NixContext.get(null).store.isValidPath(storePath);
        } catch (IOException e) {
            return false;
        }
    }

    private static com.oracle.truffle.api.RootCallTarget parseFile(NixContext ctx, String file, Set<String> scope) {
        String text;
        try {
            text = Bytes.of(Fs.readFile(file));
        } catch (IOException e) {
            throw error(e.getMessage());
        }
        return ctx.language.parse(text, file, file, null, scope);
    }

    /** Polyglot: other Truffle languages' files are imported by extension. */
    private static String foreignLanguage(String file) {
        if (file.endsWith(".js") || file.endsWith(".mjs")) return "js";
        if (file.endsWith(".py")) return "python";
        if (file.endsWith(".rb")) return "ruby";
        return null;
    }

    // --------------------------------------------------------- lookup path

    /** {@code EvalState::findFile}: the first lookup path entry whose prefix matches and that has the file. */
    static String findFile(List<String[]> lookupPath, String path) {
        for (String[] entry : lookupPath) {
            String prefix = entry[0];
            int n = prefix.length();
            boolean needSeparator = n > 0 && n < path.length();
            if (needSeparator && path.charAt(n) != '/') continue;
            if (!path.startsWith(prefix)) continue;
            String suffix = path.substring(needSeparator ? n + 1 : n);
            String root = resolveLookupPathEntry(entry[1]);
            if (root == null) continue;
            String res = resolveSymlinks(suffix.isEmpty() ? root : root + "/" + suffix, true);
            try {
                if (Fs.maybeLstat(res) != null) return res;
            } catch (IOException e) {
                throw error(e.getMessage());
            }
        }
        if (path.equals("nix/fetchurl.nix")) return "/__corepkgs__/fetchurl.nix";
        throw new NixException.Catchable("file '" + path + "' was not found in the Nix search path (add it using $NIX_PATH or -I)", null);
    }

    /**
     * {@code resolveLookupPathPath}: {@code flake:ref} is resolved by the installed {@code nix},
     * anything else is a local path that must exist. Null (with a warning) if it can't be used.
     * URLs would have to be downloaded, which nix-truffle can't do yet.
     */
    private static String resolveLookupPathEntry(String value) {
        NixContext ctx = NixContext.get(null);
        if (ctx.lookupPathCache.containsKey(value)) return ctx.lookupPathCache.get(value);
        String result = null;
        String java = Bytes.toJava(value);
        if (Settings.isPseudoUrl(java)) {
            ctx.printErr("warning: Nix search path entry '" + value + "' cannot be downloaded, ignoring");
        } else if (value.startsWith("flake:")) {
            result = resolveFlakeRef(java.substring("flake:".length()));
        } else {
            String path = NixPath.canonicalize(value.startsWith("/") ? value : nixtruffle.NixLanguage.cwd() + "/" + value);
            try {
                if (Fs.maybeLstat(resolveSymlinks(path, true)) != null) {
                    result = path;
                } else {
                    ctx.printErr("warning: Nix search path entry '" + value + "' does not exist, ignoring");
                }
            } catch (IOException e) {
                throw error(e.getMessage());
            }
        }
        ctx.lookupPathCache.put(value, result);
        return result;
    }

    /** A flake reference's source path, from {@code nix flake metadata}; null if that fails. */
    private static String resolveFlakeRef(String ref) {
        try {
            Process p = new ProcessBuilder("nix", "--extra-experimental-features", "nix-command flakes", "flake", "metadata", "--json", ref)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String json = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            return p.waitFor() == 0 && m.find() ? Bytes.fromJava(m.group(1)) : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }
}
