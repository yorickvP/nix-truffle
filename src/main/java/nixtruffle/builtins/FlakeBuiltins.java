package nixtruffle.builtins;

import nixtruffle.NixContext;
import nixtruffle.fetch.Attrs;
import nixtruffle.fetch.FetchException;
import nixtruffle.fetch.Fetcher;
import nixtruffle.fetch.FlakeRef;
import nixtruffle.fetch.Input;
import nixtruffle.fetch.LockFile;
import nixtruffle.fetch.Registry;
import nixtruffle.fs.Fs;
import nixtruffle.runtime.Builtin;
import nixtruffle.runtime.Bytes;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixFunction;
import nixtruffle.runtime.NixLambda;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.NixPath;
import nixtruffle.runtime.NixString;
import nixtruffle.runtime.Values;
import nixtruffle.store.StorePaths;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static nixtruffle.runtime.Thunk.force;
import static nixtruffle.runtime.Values.stringNoCtx;

/**
 * Flakes ({@code libflake/flake.cc}, {@code flake-primops.cc}): reading {@code flake.nix},
 * locking its inputs, and calling it with {@code call-flake.nix}.
 *
 * <p>Unlike CppNix, which reads trees lazily, every flake source is fetched to the store first, so
 * a flake's source path is always a path in the store.
 */
public final class FlakeBuiltins {
    private FlakeBuiltins() {}

    private static NixException error(String message) {
        return NixException.error(message, null);
    }

    private static Fetcher fetcher() {
        return FetchBuiltins.fetcher();
    }

    private static <T> T run(java.util.function.Supplier<T> body) {
        return FetchBuiltins.run(body);
    }

    private static void warn(String message) {
        NixContext.get(null).printErr("warning: " + message);
    }

    static void install() {
        Builtins.defFeature("getFlake", 1, "flakes", a -> getFlake(a[0]));
        Builtins.defFeature("parseFlakeRef", 1, "flakes", a -> parseFlakeRef(a[0]));
        Builtins.defFeature("flakeRefToString", 1, "flakes", a -> flakeRefToString(a[0]));
    }

    // ---------------------------------------------------------- primops

    private static Object parseFlakeRef(Object arg) {
        String s = stringNoCtx(arg);
        Fetcher f = fetcher();
        Attrs attrs = run(() -> FlakeRef.parse(f, s, null, true, true, false).toAttrs());
        TreeMap<String, Object> m = new TreeMap<>();
        for (String k : attrs.keySet()) {
            Object v = attrs.get(k);
            m.put(k, v instanceof Attrs.Lazy l ? l.get() : v);
        }
        return NixAttrs.fromMap(m);
    }

    private static Object flakeRefToString(Object arg) {
        NixAttrs args = Values.attrs(arg);
        Attrs attrs = new Attrs();
        for (int i = 0; i < args.size(); i++) {
            String name = args.keys[i];
            Object v = args.forceAt(i);
            if (v instanceof Long l) {
                if (l < 0) throw error("negative value given for flake ref attr " + name + ": " + l);
                attrs.put(name, l);
            } else if (v instanceof Boolean b) {
                attrs.put(name, b);
            } else if (NixString.is(v)) {
                attrs.put(name, NixString.value(v));
            } else {
                throw error("flake reference attribute sets may only contain integers, Booleans, and strings, but attribute '"
                        + name + "' is " + Values.typeName(v));
            }
        }
        Fetcher f = fetcher();
        return run(() -> FlakeRef.fromAttrs(f, attrs).toString());
    }

    private static Object getFlake(Object arg) {
        Object v = force(arg);
        NixContext ctx = NixContext.get(null);
        boolean pure = ctx.settings.getBool("pure-eval");
        LockFlags flags = new LockFlags();
        flags.useRegistries = !pure && ctx.settings.getBool("use-registries");
        flags.allowUnlocked = !pure;
        Fetcher f = fetcher();
        if (v instanceof NixPath) {
            String path = FileBuiltins.realisePath(v, FileBuiltins.Symlinks.FULL);
            return callFlake(run(() -> lockFlakeAt(f, path, flags)));
        }
        String s = stringNoCtx(v);
        FlakeRef ref = run(() -> FlakeRef.parse(f, s, null, true, true, false));
        if (pure && !ref.input.isLocked(f)) {
            throw error("cannot call 'getFlake' on unlocked flake reference '" + s + "' (use --impure to override)");
        }
        // Backwards compatibility: a path in a tree fetched earlier (e.g. an input's outPath
        // without its context) is read in place, like a path value.
        if (ref.input.getType().equals("path")) {
            String sourcePath = run(ref.input::getSourcePath);
            String storePath = StorePaths.toStorePath(sourcePath);
            if (storePath != null && f.isMounted(storePath)) {
                String path = ref.subdir.isEmpty() ? sourcePath : under(sourcePath, ref.subdir);
                return callFlake(run(() -> lockFlakeAt(f, path, flags)));
            }
        }
        return callFlake(run(() -> lockFlake(f, ref, flags)));
    }

    /** {@code flake:ref} in the lookup path: the source it resolves to (its subdirectory is ignored). */
    static String resolveLookupPathFlake(String ref) {
        Fetcher f = fetcher();
        if (!f.flakesEnabled()) {
            throw error("experimental Nix feature 'flakes' is disabled; add '--extra-experimental-features flakes' to enable it");
        }
        return run(() -> {
            FlakeRef r = FlakeRef.parse(f, ref, null, true, false, false);
            Input resolved = (Input) Registry.lookup(f, r.input, Registry.Use.ALL)[0];
            return resolved.fetch(f).storePath();
        });
    }

    // ---------------------------------------------------------- reading

    /** An input declaration in {@code flake.nix}. */
    static final class FlakeInput {
        FlakeRef ref;
        boolean isFlake = true;
        List<String> follows;
        TreeMap<String, FlakeInput> overrides = new TreeMap<>();

        FlakeInput copy() {
            FlakeInput c = new FlakeInput();
            c.ref = ref;
            c.isFlake = isFlake;
            c.follows = follows;
            c.overrides = overrides;
            return c;
        }
    }

    static final class Flake {
        FlakeRef originalRef, resolvedRef, lockedRef;
        /** The {@code flake.nix} file. */
        String path;
        boolean forceDirty;
        TreeMap<String, FlakeInput> inputs = new TreeMap<>();
        Attrs selfAttrs = new Attrs();

        String dir() {
            return parent(path);
        }

        String lockFilePath() {
            return dir() + "/flake.lock";
        }
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    /** {@code dir / sub}, where {@code sub} can't escape {@code dir} (a {@code CanonPath}). */
    private static String under(String dir, String sub) {
        String rel = NixPath.canonicalize("/" + sub);
        return rel.equals("/") ? dir : (dir.equals("/") ? "" : dir) + rel;
    }

    private static Object expectType(String type, Object v) {
        Object forced = force(v);
        if (!Values.typeOf(forced).equals(type)) {
            throw new FetchException("expected " + typeDescription(type) + " but got " + Values.typeName(forced));
        }
        return forced;
    }

    private static String typeDescription(String type) {
        return switch (type) {
            case "set" -> "a set";
            case "lambda" -> "a function";
            case "bool" -> "a Boolean";
            default -> "a " + type;
        };
    }

    private static Flake readFlake(Fetcher f, FlakeRef originalRef, FlakeRef resolvedRef, FlakeRef lockedRef, String rootDir, List<String> lockRootAttrPath) {
        String flakeDir = under(rootDir, resolvedRef.subdir);
        String flakePath = flakeDir + "/flake.nix";
        NixAttrs info = (NixAttrs) force(FileBuiltins.importTrivialFile(flakePath));
        Flake flake = new Flake();
        flake.originalRef = originalRef;
        flake.resolvedRef = resolvedRef;
        flake.lockedRef = lockedRef;
        flake.path = flakePath;
        Object description = info.getRaw("description");
        if (description != null) expectType("string", description);
        Object inputs = info.getRaw("inputs");
        if (inputs != null) parseFlakeInputs(f, inputs, lockRootAttrPath, flakeDir, true, flake.inputs, flake.selfAttrs);
        Object outputs = info.getRaw("outputs");
        if (outputs == null) throw new FetchException("flake '" + resolvedRef + "' lacks attribute 'outputs'");
        Object fn = force(outputs);
        if (!(fn instanceof NixFunction)) throw new FetchException("expected a function but got " + Values.typeName(fn));
        if (fn instanceof NixLambda l && l.info.hasFormals()) {
            for (String formal : l.info.formals()) {
                if (!formal.equals("self") && !flake.inputs.containsKey(formal)) {
                    FlakeInput input = new FlakeInput();
                    input.ref = FlakeRef.parse(f, formal, null, false, true, false);
                    flake.inputs.put(formal, input);
                }
            }
        }
        Object nixConfig = info.getRaw("nixConfig");
        if (nixConfig != null) checkNixConfig(expectType("set", nixConfig));
        for (String name : info.keys) {
            if (!name.equals("description") && !name.equals("inputs") && !name.equals("outputs") && !name.equals("nixConfig")) {
                throw new FetchException("flake '" + resolvedRef + "' has an unsupported attribute '" + name + "'");
            }
        }
        return flake;
    }

    /** The settings in {@code nixConfig} are only checked: nix-truffle doesn't apply them. */
    private static void checkNixConfig(Object config) {
        NixAttrs settings = (NixAttrs) config;
        for (int i = 0; i < settings.size(); i++) {
            Object v = settings.forceAt(i);
            if (NixString.is(v)) {
                stringNoCtx(v);
            } else if (v instanceof NixList list) {
                for (Object e : list.items) {
                    if (!NixString.is(force(e))) {
                        throw error("list element in flake configuration setting '" + settings.keys[i] + "' is " + Values.typeName(list) + " while a string is expected");
                    }
                    stringNoCtx(e);
                }
            } else if (!(v instanceof NixPath || v instanceof Long || v instanceof Boolean)) {
                throw error("flake configuration setting '" + settings.keys[i] + "' is " + Values.typeName(v));
            }
        }
    }

    private static void parseFlakeInputs(Fetcher f, Object value, List<String> lockRootAttrPath, String flakeDir, boolean allowSelf,
            TreeMap<String, FlakeInput> inputs, Attrs selfAttrs) {
        NixAttrs attrs = (NixAttrs) expectType("set", value);
        for (int i = 0; i < attrs.size(); i++) {
            String name = attrs.keys[i];
            if (name.equals("self")) {
                if (!allowSelf) throw new FetchException("'self' input attribute not allowed");
                NixAttrs self = (NixAttrs) expectType("set", attrs.values[i]);
                for (int j = 0; j < self.size(); j++) parseFlakeInputAttr(self.keys[j], self.forceAt(j), selfAttrs);
            } else {
                inputs.put(name, parseFlakeInput(f, attrs.values[i], lockRootAttrPath, flakeDir));
            }
        }
    }

    private static void parseFlakeInputAttr(String name, Object v, Attrs attrs) {
        if (NixString.is(v)) {
            attrs.put(name, NixString.value(v));
        } else if (v instanceof Boolean b) {
            attrs.put(name, b);
        } else if (v instanceof Long l) {
            if (l < 0) throw error("negative value given for flake input attribute " + name + ": " + l);
            attrs.put(name, l);
        } else if (name.equals("publicKeys")) {
            throw error("experimental Nix feature 'verified-fetches' is disabled; add '--extra-experimental-features verified-fetches' to enable it");
        } else {
            throw error("flake input attribute '" + name + "' is " + Values.typeName(v) + " while a string, Boolean, or integer is expected");
        }
    }

    private static FlakeInput parseFlakeInput(Fetcher f, Object value, List<String> lockRootAttrPath, String flakeDir) {
        NixAttrs decl = (NixAttrs) expectType("set", value);
        FlakeInput input = new FlakeInput();
        Attrs attrs = new Attrs();
        String url = null;
        for (int i = 0; i < decl.size(); i++) {
            String name = decl.keys[i];
            switch (name) {
                case "url" -> {
                    Object v = decl.forceAt(i);
                    if (NixString.is(v)) {
                        url = NixString.value(v);
                    } else if (v instanceof NixPath p) {
                        url = "path:" + makeRelative(flakeDir, p.path);
                    } else {
                        throw new FetchException("expected a string or a path but got " + Values.typeName(v));
                    }
                    attrs.put("url", url);
                }
                case "flake" -> input.isFlake = (Boolean) expectType("bool", decl.values[i]);
                case "inputs" -> parseFlakeInputs(f, decl.values[i], lockRootAttrPath, flakeDir, false, input.overrides, new Attrs());
                case "follows" -> {
                    List<String> follows = new ArrayList<>(lockRootAttrPath);
                    follows.addAll(LockFile.parseInputAttrPath(stringOf(expectType("string", decl.values[i]))));
                    input.follows = follows;
                }
                default -> parseFlakeInputAttr(name, decl.forceAt(i), attrs);
            }
        }
        if (attrs.containsKey("type")) {
            input.ref = FlakeRef.fromAttrs(f, attrs);
        } else {
            attrs.remove("url");
            if (!attrs.isEmpty()) throw new FetchException("unexpected flake input attribute '" + attrs.firstKey() + "'");
            if (url != null) input.ref = FlakeRef.parse(f, url, null, true, input.isFlake, true);
        }
        if (input.ref != null && input.follows != null) throw new FetchException("flake input has both a flake reference and a follows attribute");
        return input;
    }

    private static String stringOf(Object v) {
        return NixString.value(v);
    }

    /** {@code CanonPath::makeRelative}: {@code path} relative to {@code dir}, with {@code ..} as needed. */
    private static String makeRelative(String dir, String path) {
        List<String> a = components(dir), b = components(path);
        int common = 0;
        while (common < a.size() && common < b.size() && a.get(common).equals(b.get(common))) common++;
        if (common == a.size() && common == b.size()) return ".";
        List<String> parts = new ArrayList<>();
        for (int i = common; i < a.size(); i++) parts.add("..");
        parts.addAll(b.subList(common, b.size()));
        return String.join("/", parts);
    }

    private static List<String> components(String path) {
        List<String> out = new ArrayList<>();
        for (String c : path.split("/")) if (!c.isEmpty()) out.add(c);
        return out;
    }

    // --------------------------------------------------------- fetching

    /** A fetched input: where it is, the input the registries resolved it to, and the locked one. */
    record Resolved(String storePath, Input resolved, Input locked, Attrs extra) {}

    /** {@code InputCache::getAccessor} and {@code mountInput}: fetches a (possibly indirect) input. */
    private static Resolved fetchInput(Fetcher f, Input original, Registry.Use use) {
        Input resolved = original;
        Attrs extra = new Attrs();
        if (!original.isDirect()) {
            if (use == Registry.Use.NO) {
                throw new FetchException("'" + original + "' is an indirect flake reference, but registry lookups are not allowed");
            }
            Object[] r = Registry.lookup(f, original, use);
            resolved = (Input) r[0];
            extra = (Attrs) r[1];
        }
        Fetcher.Fetched fetched = resolved.fetch(f);
        f.mount(fetched.storePath());
        Input locked = fetched.locked().withAttrs(fetched.locked().attrs.copy());
        var expected = original.getNarHash();
        if (expected != null && !expected.equals(locked.getNarHash())) {
            throw new FetchException("NAR hash mismatch in input '" + original + "', expected '" + expected.sri() + "' but got '" + locked.getNarHash().sri() + "'");
        }
        return new Resolved(fetched.storePath(), resolved, locked, extra);
    }

    /** {@code getFlake}: fetches a flake and reads its {@code flake.nix}. */
    private static Flake getFlake(Fetcher f, FlakeRef originalRef, Registry.Use use, List<String> lockRootAttrPath) {
        Resolved r = fetchInput(f, originalRef.input, use);
        String dir = r.extra.getStr("dir");
        String subdir = dir != null ? dir : originalRef.subdir;
        FlakeRef resolvedRef = new FlakeRef(r.resolved, subdir);
        FlakeRef lockedRef = new FlakeRef(r.locked, subdir);
        Flake flake = readFlake(f, originalRef, resolvedRef, lockedRef, r.storePath, lockRootAttrPath);
        // `inputs.self` attributes (submodules, lfs) change how the flake is fetched.
        Attrs newAttrs = lockedRef.input.attrs.copy();
        for (Map.Entry<String, Object> a : flake.selfAttrs.entrySet()) {
            if (!a.getKey().equals("submodules") && !a.getKey().equals("lfs")) {
                throw new FetchException("flake 'self' attribute '" + a.getKey() + "' is not supported");
            }
            newAttrs.put(a.getKey(), a.getValue());
        }
        if (!newAttrs.sameAs(lockedRef.input.attrs)) {
            newAttrs.remove("narHash");
            Resolved r2 = fetchInput(f, Input.fromAttrs(f, newAttrs), Registry.Use.NO);
            flake = readFlake(f, originalRef, resolvedRef, new FlakeRef(r2.locked, subdir), r2.storePath, lockRootAttrPath);
        }
        return flake;
    }

    private static LockFile readLockFile(Fetcher f, String path) {
        try {
            if (Fs.maybeLstat(path) == null) return new LockFile();
            return LockFile.parse(f, Bytes.of(Fs.readFile(path)), path);
        } catch (IOException e) {
            throw new FetchException(e.getMessage());
        }
    }

    // ---------------------------------------------------------- locking

    /** {@code LockFlags}, as far as nix-truffle uses them. */
    public static final class LockFlags {
        public boolean updateLockFile;
        public boolean writeLockFile;
        public boolean useRegistries = true;
        public boolean allowUnlocked = true;
        public boolean failOnUnlocked;
    }

    record LockedFlake(Flake flake, LockFile lockFile, IdentityHashMap<LockFile.Node, String> nodePaths) {}

    private record OverrideTarget(FlakeInput input, String sourcePath, List<String> parentInputAttrPath) {}

    static LockedFlake lockFlake(Fetcher f, FlakeRef topRef, LockFlags flags) {
        Registry.Use useTop = flags.useRegistries ? Registry.Use.ALL : Registry.Use.NO;
        return lockFlake(f, topRef, flags, getFlake(f, topRef, useTop, List.of()));
    }

    /** {@code getFlake} on a path: the flake there, under a fake reference. */
    private static LockedFlake lockFlakeAt(Fetcher f, String flakeDir, LockFlags flags) {
        FlakeRef fakeRef = FlakeRef.parse(f, "flake:get-flake", null, false, true, false);
        return lockFlake(f, fakeRef, flags, readFlake(f, fakeRef, fakeRef, fakeRef, flakeDir, List.of()));
    }

    private static LockedFlake lockFlake(Fetcher f, FlakeRef topRef, LockFlags flags, Flake flake0) {
        if (!f.flakesEnabled()) {
            throw new FetchException("experimental Nix feature 'flakes' is disabled; add '--extra-experimental-features flakes' to enable it");
        }
        Locker l = new Locker(f, flags);
        Flake flake = flake0;
        try {
            LockFile oldLockFile = readLockFile(f, flake.lockFilePath());
            LockFile newLockFile = new LockFile();
            l.nodePaths.put(newLockFile.root, flake.dir());
            l.computeLocks(flake.inputs, newLockFile.root, List.of(), oldLockFile.root, List.of(), flake.path, false);
            newLockFile.check();

            String sourcePath = topRef.input.getSourcePath();
            if (!newLockFile.sameAs(oldLockFile)) {
                String diff = LockFile.diff(oldLockFile, newLockFile).stripTrailing();
                if (flags.writeLockFile) {
                    if (sourcePath == null) {
                        throw new FetchException("cannot write modified lock file of flake '" + topRef + "' (use '--no-write-lock-file' to ignore)");
                    }
                    FlakeRef unlocked = newLockFile.isUnlocked(f);
                    if (unlocked != null) {
                        if (flags.failOnUnlocked) {
                            throw new FetchException("Not writing lock file of flake '" + topRef + "' because it has an unlocked input ('" + unlocked
                                    + "'). Use '--allow-dirty-locks' to allow this anyway.");
                        }
                        if (f.settings.getBool("warn-dirty")) {
                            warn("not writing lock file of flake '" + topRef + "' because it has an unlocked input ('" + unlocked + "')");
                        }
                    } else {
                        if (!flags.updateLockFile) {
                            throw new FetchException("flake '" + topRef + "' requires lock file changes but they're not allowed due to '--no-update-lock-file'");
                        }
                        String relPath = (topRef.subdir.isEmpty() ? "" : topRef.subdir + "/") + "flake.lock";
                        String outputPath = sourcePath + "/" + relPath;
                        boolean exists;
                        try {
                            exists = Fs.maybeLstat(outputPath) != null;
                        } catch (IOException e) {
                            exists = false;
                        }
                        // Quoted like a std::filesystem::path.
                        String quoted = "\"" + outputPath + "\"";
                        if (exists) {
                            warn(diff.isEmpty() ? "updating lock file " + quoted : "updating lock file " + quoted + ":\n" + diff);
                        } else {
                            warn("creating lock file " + quoted + ": \n" + diff);
                        }
                        topRef.input.putFile(relPath, Bytes.get(newLockFile + "\n"));
                        // Writing the lock file changed the flake's source: read it again.
                        flake = getFlake(f, topRef, flags.useRegistries ? Registry.Use.ALL : Registry.Use.NO, List.of());
                    }
                } else {
                    warn("not writing modified lock file of flake '" + topRef + "':\n" + diff);
                    flake.forceDirty = true;
                }
            }
            return new LockedFlake(flake, newLockFile, l.nodePaths);
        } catch (FetchException e) {
            throw new FetchException(e.getMessage() + "\n       … while updating the lock file of flake '" + describe(flake.lockedRef) + "'", e);
        }
    }

    private static String describe(FlakeRef ref) {
        try {
            return ref.toString();
        } catch (FetchException e) {
            return Attrs.Json.write(ref.toAttrs());
        }
    }

    /** The state of {@code computeLocks}. */
    private static final class Locker {
        final Fetcher f;
        final LockFlags flags;
        final Registry.Use useInputs;
        final TreeMap<List<String>, OverrideTarget> overrides = new TreeMap<>(LockFile::comparePaths);
        final IdentityHashMap<LockFile.Node, String> nodePaths = new IdentityHashMap<>();
        final List<FlakeRef> parents = new ArrayList<>();

        Locker(Fetcher f, LockFlags flags) {
            this.f = f;
            this.flags = flags;
            this.useInputs = flags.useRegistries ? Registry.Use.LIMITED : Registry.Use.NO;
        }

        private static List<String> append(List<String> prefix, String elem) {
            List<String> p = new ArrayList<>(prefix);
            p.add(elem);
            return p;
        }

        private void addOverrides(FlakeInput input, List<String> prefix, String sourcePath, List<String> inputAttrPathPrefix) {
            for (Map.Entry<String, FlakeInput> e : input.overrides.entrySet()) {
                List<String> path = append(prefix, e.getKey());
                FlakeInput o = e.getValue();
                if (o.ref != null || o.follows != null) overrides.putIfAbsent(path, new OverrideTarget(o, sourcePath, inputAttrPathPrefix));
                addOverrides(o, path, sourcePath, inputAttrPathPrefix);
            }
        }

        /**
         * Locks the inputs of {@code node}: {@code flakeInputs} (from its flake.nix or lock file) at
         * {@code prefix}, copying from {@code oldNode} where the declaration didn't change.
         * {@code follows} are relative to {@code followsPrefix}; {@code sourcePath} is the node's
         * {@code flake.nix}.
         */
        void computeLocks(TreeMap<String, FlakeInput> flakeInputs, LockFile.Node node, List<String> prefix, LockFile.Node oldNode,
                List<String> followsPrefix, String sourcePath, boolean trustLock) {
            for (Map.Entry<String, FlakeInput> e : flakeInputs.entrySet()) addOverrides(e.getValue(), append(prefix, e.getKey()), sourcePath, prefix);

            for (List<String> path : overrides.keySet()) {
                String follow = path.get(path.size() - 1);
                if (path.subList(0, path.size() - 1).equals(prefix) && !flakeInputs.containsKey(follow)) {
                    warn("input '" + String.join("/", prefix) + "' has an override for a non-existent input '" + follow + "'");
                }
            }

            for (Map.Entry<String, FlakeInput> e : flakeInputs.entrySet()) {
                String id = e.getKey();
                List<String> path = append(prefix, id);
                try {
                    computeInput(id, e.getValue(), path, node, prefix, oldNode, followsPrefix, sourcePath, trustLock);
                } catch (FetchException ex) {
                    throw new FetchException(ex.getMessage() + "\n       … while updating the flake input '" + String.join("/", path) + "'", ex);
                }
            }
        }

        private void computeInput(String id, FlakeInput input2, List<String> path, LockFile.Node node, List<String> prefix, LockFile.Node oldNode,
                List<String> followsPrefix, String sourcePath, boolean trustLock) {
            OverrideTarget override = overrides.get(path);
            boolean hasOverride = override != null;
            FlakeInput input = hasOverride ? override.input.copy() : input2.copy();
            // Relative inputs of an override are relative to the flake that declares it.
            String overriddenSourcePath = hasOverride ? override.sourcePath : sourcePath;
            // An override keeps the input's flakeness.
            if (hasOverride) input.isFlake = input2.isFlake;

            // `follows` are resolved later: they may refer to inputs not locked yet.
            if (input.follows != null) {
                node.inputs.put(id, new ArrayList<>(input.follows));
                return;
            }
            if (input.ref == null) input.ref = FlakeRef.fromAttrs(f, Attrs.of("type", "indirect", "id", id));

            String relative = input.ref.input.isRelative();
            List<String> overriddenParentPath = relative != null ? (hasOverride ? override.parentInputAttrPath : prefix) : null;
            String resolvedPath = relative != null ? NixPath.canonicalize(parent(overriddenSourcePath) + "/" + relative) : null;

            LockFile.Locked oldLock = null;
            if (oldNode != null && oldNode.inputs.get(id) instanceof LockFile.Locked old) oldLock = old;

            if (oldLock != null && oldLock.originalRef.canonicalize().sameAs(input.ref.canonicalize())
                    && Objects.equals(oldLock.parentPath, overriddenParentPath)) {
                // The declaration didn't change: keep the lock.
                LockFile.Locked childNode = new LockFile.Locked(oldLock.lockedRef, oldLock.originalRef, oldLock.isFlake, oldLock.parentPath);
                node.inputs.put(id, childNode);
                boolean mustRefetch = false;
                TreeMap<String, FlakeInput> fakeInputs = new TreeMap<>();
                for (Map.Entry<String, Object> i : oldLock.inputs.entrySet()) {
                    if (i.getValue() instanceof LockFile.Locked locked) {
                        FlakeInput fake = new FlakeInput();
                        fake.ref = locked.originalRef;
                        fake.isFlake = locked.isFlake;
                        fakeInputs.put(i.getKey(), fake);
                    } else {
                        // The flake may have changed: a `follows` in the lock file must still be declared.
                        if (!trustLock && !overrides.containsKey(append(path, i.getKey()))) {
                            mustRefetch = true;
                            break;
                        }
                        FlakeInput fake = new FlakeInput();
                        fake.follows = new ArrayList<>(followsPrefix);
                        fake.follows.addAll(LockFile.followsPath(i.getValue()));
                        fakeInputs.put(i.getKey(), fake);
                    }
                }
                if (mustRefetch) {
                    Flake inputFlake = getInputFlake(oldLock.lockedRef, resolvedPath, path);
                    nodePaths.putIfAbsent(childNode, inputFlake.dir());
                    computeLocks(inputFlake.inputs, childNode, path, oldLock, followsPrefix, inputFlake.path, false);
                } else {
                    computeLocks(fakeInputs, childNode, path, oldLock, followsPrefix, sourcePath, true);
                }
                return;
            }

            // A new lock file entry: fetch the input.
            if (!flags.allowUnlocked && !input.ref.input.isLocked(f) && relative == null) {
                throw new FetchException("cannot update unlocked flake input '" + String.join("/", path) + "' in pure mode");
            }
            FlakeRef ref = input.ref;
            if (input.isFlake) {
                Flake inputFlake = getInputFlake(input.ref, resolvedPath, path);
                LockFile.Locked childNode = new LockFile.Locked(inputFlake.lockedRef, ref, true, overriddenParentPath);
                node.inputs.put(id, childNode);
                for (FlakeRef p : parents) {
                    if (p.sameAs(input.ref)) throw new FetchException("found circular import of flake '" + p + "'");
                }
                parents.add(input.ref);
                try {
                    nodePaths.putIfAbsent(childNode, inputFlake.dir());
                    computeLocks(inputFlake.inputs, childNode, path, readLockFile(f, inputFlake.lockFilePath()).root, path, inputFlake.path, false);
                } finally {
                    parents.remove(parents.size() - 1);
                }
            } else {
                String inputPath;
                FlakeRef lockedRef;
                if (resolvedPath != null) {
                    inputPath = resolvedPath;
                    lockedRef = input.ref;
                } else {
                    Resolved r = fetchInput(f, input.ref.input, useInputs);
                    lockedRef = new FlakeRef(r.locked, input.ref.subdir);
                    inputPath = r.storePath;
                }
                LockFile.Locked childNode = new LockFile.Locked(lockedRef, ref, false, overriddenParentPath);
                nodePaths.putIfAbsent(childNode, inputPath);
                node.inputs.put(id, childNode);
            }
        }

        /** A flake input: a relative one is read from its parent's source. */
        private Flake getInputFlake(FlakeRef ref, String resolvedPath, List<String> path) {
            if (resolvedPath != null) return readFlake(f, ref, ref, ref, resolvedPath, path);
            return getFlake(f, ref, useInputs, path);
        }
    }

    // ---------------------------------------------------------- calling

    /** {@code callFlake}: the flake's outputs and metadata, through {@code call-flake.nix}. */
    private static Object callFlake(LockedFlake locked) {
        NixContext ctx = NixContext.get(null);
        LockFile.Dumped dumped = locked.lockFile().toJSON();
        String lockFileStr = nixtruffle.util.Json.write(dumped.json(), 2);
        TreeMap<String, Object> overrides = new TreeMap<>();
        for (Map.Entry<LockFile.Node, String> e : locked.nodePaths().entrySet()) {
            String sourcePath = e.getValue();
            String storePath = StorePaths.toStorePath(sourcePath);
            if (storePath == null) throw error("path '" + sourcePath + "' is not in the Nix store");
            LockFile.Node node = e.getKey();
            Input input = node instanceof LockFile.Locked l ? l.lockedRef.input : locked.flake().lockedRef.input;
            NixAttrs sourceInfo = FetchBuiltins.emitTreeAttrs(storePath, input, false,
                    !(node instanceof LockFile.Locked) && locked.flake().forceDirty);
            String subdir = sourcePath.substring(storePath.length());
            TreeMap<String, Object> o = new TreeMap<>();
            o.put("sourceInfo", sourceInfo);
            o.put("dir", subdir.startsWith("/") ? subdir.substring(1) : subdir);
            String key = dumped.keys().get(node);
            if (key == null) throw new IllegalStateException("lock file node without a key");
            overrides.put(key, NixAttrs.fromMap(o));
        }
        Builtin fetchFinalTree = new Builtin("fetchFinalTree", 1, a -> FetchBuiltins.fetchFinalTree(a[0]));
        return Builtins.call(ctx.corepkgValue("call-flake.nix"), lockFileStr, NixAttrs.fromMap(overrides), fetchFinalTree);
    }

    // ------------------------------------------------------ nix flake lock

    /** {@code nix flake lock}: locks the flake at {@code ref} and writes its lock file. */
    public static void lock(String ref) {
        Fetcher f = fetcher();
        NixContext ctx = NixContext.get(null);
        LockFlags flags = new LockFlags();
        flags.writeLockFile = true;
        flags.updateLockFile = true;
        flags.failOnUnlocked = true;
        flags.useRegistries = ctx.settings.getBool("use-registries");
        run(() -> {
            FlakeRef r = FlakeRef.parse(f, ref, nixtruffle.NixLanguage.cwd(), false, true, false);
            return lockFlake(f, r, flags);
        });
    }
}
