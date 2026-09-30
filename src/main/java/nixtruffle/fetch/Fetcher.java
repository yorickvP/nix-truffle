package nixtruffle.fetch;

import nixtruffle.Settings;
import nixtruffle.fs.Fs;
import nixtruffle.runtime.Bytes;
import nixtruffle.store.DaemonClient;
import nixtruffle.store.Hash;
import nixtruffle.store.Nar;
import nixtruffle.store.Store;
import nixtruffle.store.StorePaths;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared state of the fetchers in one evaluation: the store, settings, the persistent {@link
 * Cache}, and an in-memory cache of inputs fetched so far (libfetchers' {@code InputCache}), so
 * that fetching the same input twice gives the same tree.
 */
public final class Fetcher {
    public final Store store;
    public final Settings settings;
    public final Cache cache = new Cache();
    private final Map<String, Fetched> inputCache = new HashMap<>();
    private final Set<String> mounted = new HashSet<>();
    private Registry.Registries registries;

    public Fetcher(Store store, Settings settings) {
        this.store = store;
        this.settings = settings;
    }

    /** A fetched tree in the store, and the input with everything that locks it. */
    public record Fetched(String storePath, Input locked) {}

    /** A store path and the SHA-256 hash of its NAR serialisation. */
    public record Tree(String storePath, Hash narHash) {}

    public long tarballTtl() {
        return settings.getLong("tarball-ttl", 3600);
    }

    public boolean flakesEnabled() {
        return settings.isEnabled("flakes");
    }

    Registry.Registries registries() {
        if (registries == null) registries = new Registry.Registries(this);
        return registries;
    }

    /** {@code downloadFile}: a URL's contents as a flat store path (cached for {@code tarball-ttl}). */
    public String downloadFile(String url, String name) {
        return CurlScheme.downloadFile(this, url, name, Map.of()).storePath();
    }

    // --------------------------------------------------------- input cache

    Fetched cachedFetch(Input input) {
        return inputCache.get(Attrs.Json.write(input.attrs));
    }

    void cacheFetch(Input input, Fetched f) {
        inputCache.put(Attrs.Json.write(input.attrs), f);
    }

    /**
     * CppNix's {@code mountInput}: trees that {@code fetchTree}, {@code fetchTarball} and flakes
     * fetched are "mounted" at their store path, which {@code getFlake} on such a path looks for.
     */
    public void mount(String storePath) {
        mounted.add(storePath);
        onMount.accept(storePath);
    }

    /** Told about every mounted tree (pure evaluation allows reading them). */
    public java.util.function.Consumer<String> onMount = p -> {};

    public boolean isMounted(String storePath) {
        return mounted.contains(storePath);
    }

    // ------------------------------------------------------------- store

    /**
     * Adds the file tree at {@code path} (a directory, file or symlink; a byte string) to the store
     * as {@code name}, NAR-hashed, and returns its store path and NAR hash. Nothing is copied if
     * the path is already valid.
     */
    public Tree addTree(String path, String name, Nar.Filter filter) {
        try {
            Hash narHash = Nar.hash(path, filter);
            String storePath = StorePaths.fixedOutputPath(true, narHash, name);
            addToStore(storePath, name, "fixed:r:sha256", out -> Nar.dump(path, filter, out));
            return new Tree(storePath, narHash);
        } catch (IOException e) {
            throw new FetchException("cannot add '" + path + "' to the store: " + e.getMessage());
        }
    }

    /**
     * {@link #addTree} for a tree whose NAR hash is known (a cached download): if the store path
     * for it is valid already, the tree isn't read again.
     */
    public Tree addHashedTree(String path, String name, Hash narHash) {
        String storePath = StorePaths.fixedOutputPath(true, narHash, name);
        if (isValid(storePath)) {
            addTempRoot(storePath);
            store.markValid(storePath);
            return new Tree(storePath, narHash);
        }
        return addTree(path, name, null);
    }

    /** Adds a file's contents to the store as a flat, SHA-256 content-addressed path. */
    public String addFlatFile(byte[] data, String name) {
        String storePath = StorePaths.fixedOutputPath(false, Hash.of("sha256", data), name);
        try {
            addToStore(storePath, name, "fixed:sha256", out -> out.write(data));
        } catch (IOException e) {
            throw new FetchException("cannot add '" + name + "' to the store: " + e.getMessage());
        }
        return storePath;
    }

    private void addToStore(String storePath, String name, String method, DaemonClient.Dump dump) throws IOException {
        String err = StorePaths.checkName(name);
        if (err != null) throw new FetchException(err);
        DaemonClient daemon = store.daemon();
        if (!daemon.isValidPath(storePath)) {
            String got = daemon.addToStore(name, method, new TreeSet<>(), dump);
            if (!got.equals(storePath)) throw new IOException("store path mismatch: computed " + storePath + " but the daemon added " + got);
        }
        daemon.addTempRoot(storePath);
        store.markValid(storePath);
    }

    /** Whether a store path is valid (in the real store). */
    public boolean isValid(String storePath) {
        try {
            return store.isValidPath(storePath);
        } catch (IOException e) {
            return false;
        }
    }

    /** Protects a store path from garbage collection while we're connected. */
    public void addTempRoot(String storePath) {
        try {
            store.daemon().addTempRoot(storePath);
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ----------------------------------------------------------- scratch

    /** A fresh temporary directory (a byte string), deleted by {@link #deleteTemp}. */
    public static String tempDir(String prefix) {
        try {
            String base = Bytes.fromJava(System.getProperty("java.io.tmpdir"));
            for (int i = 0; ; i++) {
                String dir = base + "/nix-truffle-" + prefix + "-" + ProcessHandle.current().pid() + "-" + System.nanoTime() + "-" + i;
                try {
                    Fs.mkdir(dir, 0700);
                    return dir;
                } catch (Fs.Error e) {
                    if (e.errno != Fs.EEXIST) throw e;
                }
            }
        } catch (IOException e) {
            throw new FetchException("cannot create a temporary directory: " + e.getMessage());
        }
    }

    public static void deleteTemp(String dir) {
        try {
            Fs.deleteTree(dir);
        } catch (IOException ignored) {
            // leave it
        }
    }
}
