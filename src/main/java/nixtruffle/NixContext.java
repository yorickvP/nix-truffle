package nixtruffle;

import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.Node;

import nixtruffle.fs.Fs;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

public final class NixContext {
    private static final ContextReference<NixContext> REFERENCE = ContextReference.create(NixLanguage.class);

    public final NixLanguage language;
    public final Env env;
    /** Results of {@code import}, per canonical path (Nix evaluates each file once). */
    public final Map<String, Object> importCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** Lookup path entries ({@code nixpkgs=flake:nixpkgs}, URLs) resolved so far; null if unusable. */
    public final Map<String, String> lookupPathCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** {@code builtins.wasm}: modules compiled so far, by path. */
    public final Map<String, Object> wasmModules = new java.util.concurrent.ConcurrentHashMap<>();
    public final PrintStream err;
    public final nixtruffle.store.Store store = new nixtruffle.store.Store();
    private Object derivationLambda;

    public final Settings settings;
    /** The base environment: {@code builtins} and the names visible without it. */
    private java.util.Map<String, Object> globals;
    /** The names of {@link #globals}, and their values in that order. */
    private GlobalScope globalScope;
    private Object[] globalValues;

    /** {@code pure-eval}: files can only be read from store paths in {@link #allowedPaths}. */
    public final boolean pureEval;
    /** The limit of the depth of calls and recursive operations (see {@link nixtruffle.runtime.CallDepth}). */
    public final int maxCallDepth;
    /** The thread that evaluates (the one that made the context, not a worker of {@link nixtruffle.runtime.Parallel}), and its state. */
    public final Thread mainThread = Thread.currentThread();
    public final nixtruffle.runtime.EvalThread main;
    /** Evaluation on more threads ({@code eval-cores}), or null. */
    public final nixtruffle.runtime.Parallel parallel;
    /**
     * In pure evaluation, the store paths that may be read: fetched trees, and what {@code
     * toFile}, {@code builtins.path} and path interpolation added (CppNix's {@code allowPath}).
     */
    private final java.util.TreeSet<String> allowedPaths = new java.util.TreeSet<>();

    NixContext(NixLanguage language, Env env, Settings settings, boolean readOnly) {
        this.language = language;
        this.env = env;
        this.err = new PrintStream(env.err(), true);
        this.settings = settings;
        this.store.readOnly = readOnly;
        this.pureEval = settings.getBool("pure-eval");
        this.maxCallDepth = (int) Math.min(Integer.MAX_VALUE, settings.getLong("max-call-depth", 10000));
        this.main = new nixtruffle.runtime.EvalThread(maxCallDepth);
        long cores = settings.getLong("eval-cores", 1);
        if (cores == 0) cores = Runtime.getRuntime().availableProcessors();
        this.parallel = cores > 1 ? new nixtruffle.runtime.Parallel(this, (int) cores - 1) : null;
    }

    private nixtruffle.fetch.Fetcher fetcher;

    /** The fetchers' state for this evaluation (created on first use). */
    public nixtruffle.fetch.Fetcher fetcher() {
        if (fetcher == null) {
            fetcher = new nixtruffle.fetch.Fetcher(store, settings);
            fetcher.onMount = this::allowPath;
        }
        return fetcher;
    }

    /** Allows reading a store path (and everything in it) in pure evaluation. */
    public synchronized void allowPath(String storePath) {
        if (pureEval) allowedPaths.add(storePath);
    }

    /**
     * In pure evaluation, a path (a byte string) can only be accessed if it is in an allowed path
     * or is a parent directory of one, like CppNix's {@code AllowListSourceAccessor}.
     */
    public synchronized void checkAccess(String path) {
        if (!pureEval || path.startsWith("/__corepkgs__/") || allowedPaths.contains(path)) return;
        String within = allowedPaths.ceiling(path.equals("/") ? "/" : path + "/");
        if (within != null && within.startsWith(path.equals("/") ? "/" : path + "/")) return;
        for (String p = path; p.lastIndexOf('/') > 0; ) {
            p = p.substring(0, p.lastIndexOf('/'));
            if (allowedPaths.contains(p)) return;
        }
        throw new nixtruffle.runtime.NixException.Restricted(
                "access to absolute path '" + path + "' is forbidden in pure evaluation mode (use '--impure' to override)");
    }

    /** A name in the base environment (after lexical scopes, before {@code with}), or null. */
    public Object global(String name) {
        if (globals == null) initGlobals();
        return globals.get(name);
    }

    /** The names in the base environment, which code parsed in this context is resolved against. */
    public GlobalScope globalScope() {
        if (globalScope == null) initGlobals();
        return globalScope;
    }

    /** Global {@code index} of {@code scope} (see {@link nixtruffle.nodes.GlobalReadNode}). */
    public Object globalValue(GlobalScope scope, int index) {
        if (scope == globalScope) return globalValues[index];
        return globalByName(scope.name(index));
    }

    /** Code parsed for another scope: another context's settings enabled other primops. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object globalByName(String name) {
        Object value = global(name);
        if (value == null) throw nixtruffle.runtime.NixException.error("undefined variable '" + name + "'", null);
        return value;
    }

    private void initGlobals() {
        globals = nixtruffle.builtins.Builtins.createBaseEnv(this);
        globalScope = language.globalScope(globals);
        globalValues = new Object[globalScope.size()];
        for (int i = 0; i < globalValues.length; i++) globalValues[i] = globals.get(globalScope.name(i));
    }

    /** Prints a message (a byte string, see {@link nixtruffle.runtime.Bytes#output}) to stderr. */
    public void printErr(String message) {
        byte[] b = nixtruffle.runtime.Bytes.output(message + "\n");
        err.write(b, 0, b.length);
        err.flush();
    }

    public static NixContext get(Node node) {
        return REFERENCE.get(node);
    }

    /** {@code "${./foo}"}: the store path the file tree at {@code path} (a byte string) is copied to. */
    public String copyPathToStore(String path, Node location) {
        if (path.endsWith(".drv")) throw nixtruffle.runtime.NixException.error("file names are not allowed to end in '.drv'", location);
        checkAccess(path);
        try {
            if (path.startsWith("/__corepkgs__/")) throw nixtruffle.runtime.NixException.error("cannot copy '" + path + "' to the store", location);
            if (Fs.maybeLstat(path) == null) {
                throw nixtruffle.runtime.NixException.error("path '" + path + "' does not exist", location);
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            String nameError = nixtruffle.store.StorePaths.checkName(name);
            if (nameError != null) throw nixtruffle.runtime.NixException.error(nameError, location);
            String storePath = store.copyPathToStore(path, name);
            allowPath(storePath);
            return storePath;
        } catch (java.io.IOException e) {
            throw nixtruffle.runtime.NixException.error("cannot copy '" + path + "' to the store: " + e.getMessage(), location);
        }
    }

    /** Nix source of a corepkgs file ({@code <nix/fetchurl.nix>}, {@code derivation}), or null. */
    public static String corepkg(String name) {
        try (var in = NixContext.class.getResourceAsStream("/nixtruffle/corepkgs/" + name)) {
            return in == null ? null : nixtruffle.runtime.Bytes.of(in.readAllBytes());
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Evaluates a corepkgs file once (they are parsed like any file under {@code /__corepkgs__}). */
    public Object corepkgValue(String name) {
        String key = "/__corepkgs__/" + name;
        Object cached = importCache.get(key);
        if (cached != null) return cached;
        String text = corepkg(name);
        if (text == null) throw nixtruffle.runtime.NixException.error("file '" + key + "' does not exist", null);
        Object result = language.parse(text, key, key, null, null).call();
        // Another thread may have evaluated it too: everyone gets the first one.
        Object first = importCache.putIfAbsent(key, result);
        return first != null ? first : result;
    }

    /** The {@code derivation} function: the derivation.nix wrapper around derivationStrict. */
    public Object derivationLambda() {
        if (derivationLambda == null) derivationLambda = corepkgValue("derivation.nix");
        return derivationLambda;
    }
}
