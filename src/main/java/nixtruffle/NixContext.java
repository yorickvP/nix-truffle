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
    public final Map<String, Object> importCache = new HashMap<>();
    /** Lookup path entries ({@code nixpkgs=flake:nixpkgs}, URLs) resolved so far; null if unusable. */
    public final Map<String, String> lookupPathCache = new HashMap<>();
    public final PrintStream err;
    public final nixtruffle.store.Store store = new nixtruffle.store.Store();
    private Object derivationLambda;

    public final Settings settings;
    /** The base environment: {@code builtins} and the names visible without it. */
    private java.util.Map<String, Object> globals;

    NixContext(NixLanguage language, Env env, Settings settings, boolean readOnly) {
        this.language = language;
        this.env = env;
        this.err = new PrintStream(env.err(), true);
        this.settings = settings;
        this.store.readOnly = readOnly;
    }

    private nixtruffle.fetch.Fetcher fetcher;

    /** The fetchers' state for this evaluation (created on first use). */
    public nixtruffle.fetch.Fetcher fetcher() {
        if (fetcher == null) fetcher = new nixtruffle.fetch.Fetcher(store, settings);
        return fetcher;
    }

    /** A name in the base environment (after lexical scopes, before {@code with}), or null. */
    public Object global(String name) {
        if (globals == null) globals = nixtruffle.builtins.Builtins.createBaseEnv(this);
        return globals.get(name);
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
        try {
            if (path.startsWith("/__corepkgs__/")) throw nixtruffle.runtime.NixException.error("cannot copy '" + path + "' to the store", location);
            if (Fs.maybeLstat(path) == null) {
                throw nixtruffle.runtime.NixException.error("path '" + path + "' does not exist", location);
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            String nameError = nixtruffle.store.StorePaths.checkName(name);
            if (nameError != null) throw nixtruffle.runtime.NixException.error(nameError, location);
            return store.copyPathToStore(path, name);
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
        importCache.put(key, result);
        return result;
    }

    /** The {@code derivation} function: the derivation.nix wrapper around derivationStrict. */
    public Object derivationLambda() {
        if (derivationLambda == null) derivationLambda = corepkgValue("derivation.nix");
        return derivationLambda;
    }
}
