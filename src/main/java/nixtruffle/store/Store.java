package nixtruffle.store;

import nixtruffle.fs.Fs;
import nixtruffle.runtime.Bytes;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What evaluation has put "into the store": derivations, text files and copied sources, by store
 * path. Paths are computed exactly like Nix does, without touching the real store; they are
 * written through the daemon when something needs them to exist (reading them, checking that
 * they're valid, instantiation), unless the store is {@link #readOnly}.
 *
 * <p>Threads use it without a lock: its registries are concurrent maps, and paths and hashes are
 * functions of what they're computed from, so two threads that compute the same one at once get
 * the same result. Nothing here evaluates Nix code (source filters are evaluated once, while
 * hashing, and replayed when writing), so a thread never holds a lock here while it waits for
 * another thread's thunk.
 */
public final class Store {
    /** How to produce a store path's contents. */
    public sealed interface Entry {
        SortedSet<String> references();
    }

    public record Text(String name, String contents, SortedSet<String> references) implements Entry {}

    /** A local file tree ({@code path} is a byte string), NAR-hashed ({@code recursive}) or flat. */
    public record Source(String name, String path, Nar.Filter filter, boolean recursive) implements Entry {
        public SortedSet<String> references() { return new TreeSet<>(); }
    }

    public record Drv(Derivation drv, String contents) implements Entry {
        public SortedSet<String> references() { return drv.references(); }
    }

    public final Map<String, Entry> entries = new ConcurrentHashMap<>();
    /** Output hashes modulo fixed-output derivations, per drv path and output (hex). */
    private final Map<String, Map<String, String>> drvHashes = new ConcurrentHashMap<>();
    /** {@code "${./foo}"}: local path -> store path. */
    private final Map<String, String> srcToStore = new ConcurrentHashMap<>();
    /** Store paths known to be valid in the real store (written by us or checked). */
    private final Set<String> valid = ConcurrentHashMap.newKeySet();

    /** Don't write anything to the real store (like Nix's read-only mode). */
    public boolean readOnly;
    private DaemonClient daemon;

    /** The daemon connection, opened on first use. */
    public synchronized DaemonClient daemon() throws IOException {
        if (daemon == null || daemon.isBroken()) daemon = new DaemonClient();
        return daemon;
    }

    /** The daemon connection, or null without a daemon: then validity is judged by the file system. */
    private DaemonClient daemonOrNull() {
        try {
            return daemon();
        } catch (IOException e) {
            return null;
        }
    }

    /** Whether a store path exists: created by this evaluation, or valid in the real store. */
    public boolean isValidPath(String path) throws IOException {
        if (entries.containsKey(path) || valid.contains(path)) return true;
        DaemonClient d = daemonOrNull();
        boolean ok = d != null ? d.isValidPath(path) : Fs.maybeLstat(path) != null;
        if (ok) valid.add(path);
        return ok;
    }

    /**
     * Makes sure a store path this evaluation created exists in the real store, so that it can be
     * read (does nothing for other paths, and in read-only mode). Threads that write overlapping
     * closures at once may both add a path; the daemon locks it, and the second finds it there.
     */
    public void ensureWritten(String storePath) throws IOException {
        if (readOnly || !entries.containsKey(storePath) || valid.contains(storePath)) return;
        Set<String> done = new HashSet<>();
        writeClosure(daemon(), storePath, done);
        valid.addAll(done);
    }

    /** Records a path that has been added to the real store by other means (fetchers). */
    public void markValid(String storePath) {
        valid.add(storePath);
    }

    public Derivation derivation(String drvPath) {
        if (entries.get(drvPath) instanceof Drv d) return d.drv();
        throw new IllegalStateException("unknown derivation '" + drvPath + "'");
    }

    /** Port of libstore's {@code hashDerivationModulo}: output name -> hex hash. */
    public Map<String, String> hashModulo(Derivation drv, boolean maskOutputs) {
        Map<String, String> result = new TreeMap<>();
        if (drv.isFixedOutput()) {
            Derivation.Output out = drv.outputs.get("out");
            result.put("out", Hash.sha256("fixed:out:" + out.hashAlgo() + ":" + out.hash() + ":" + out.path()).hex());
            return result;
        }
        TreeMap<String, TreeSet<String>> inputs = new TreeMap<>();
        for (Map.Entry<String, TreeSet<String>> i : drv.inputDrvs.entrySet()) {
            Map<String, String> h = pathHashModulo(i.getKey());
            for (String output : i.getValue()) {
                String hash = h.get(output);
                if (hash == null) throw new IllegalStateException("no hash for output '" + output + "' of derivation '" + i.getKey() + "'");
                inputs.computeIfAbsent(hash, k -> new TreeSet<>()).add(output);
            }
        }
        String hash = Hash.sha256(drv.unparse(maskOutputs, inputs)).hex();
        for (String output : drv.outputs.keySet()) result.put(output, hash);
        return result;
    }

    private Map<String, String> pathHashModulo(String drvPath) {
        // Not computeIfAbsent: computing it computes those of the inputs.
        Map<String, String> h = drvHashes.get(drvPath);
        if (h == null) {
            h = hashModulo(derivation(drvPath), false);
            drvHashes.putIfAbsent(drvPath, h);
        }
        return h;
    }

    /** Writes a derivation "to the store": computes its path and remembers it. */
    public String addDerivation(Derivation drv) {
        String contents = drv.unparse(false, null);
        String path = StorePaths.textPath(drv.name + ".drv", contents, drv.references());
        entries.putIfAbsent(path, new Drv(drv, contents));
        if (!drvHashes.containsKey(path)) drvHashes.putIfAbsent(path, hashModulo(drv, false));
        return path;
    }

    public String addText(String name, String contents, SortedSet<String> references) {
        String path = StorePaths.textPath(name, contents, references);
        entries.putIfAbsent(path, new Text(name, contents, references));
        return path;
    }

    /**
     * Store path of a local file tree, as {@code builtins.path} / {@code "${./foo}"} would add it.
     * The filter is called once per file, here, as in Nix: writing the tree replays its answers.
     */
    public String addSource(String name, String path, Nar.Filter filter, boolean recursive) throws IOException {
        Nar.Filter replay = null;
        if (filter != null) {
            Set<String> included = new HashSet<>();
            Nar.Filter evaluated = filter;
            filter = (p, type) -> {
                boolean in = evaluated.include(p, type);
                if (in) included.add(p);
                return in;
            };
            replay = (p, type) -> included.contains(p);
        }
        Hash hash = recursive ? Nar.hash(path, filter) : Hash.of("sha256", Fs.readFile(path));
        String storePath = StorePaths.fixedOutputPath(recursive, hash, name);
        entries.putIfAbsent(storePath, new Source(name, path, replay, recursive));
        return storePath;
    }

    /** {@code "${./foo}"}: copies are cached per path, like Nix's srcToStore. */
    public String copyPathToStore(String path, String name) throws IOException {
        String cached = srcToStore.get(path);
        if (cached != null) return cached;
        String storePath = addSource(name, path, null, true);
        String raced = srcToStore.putIfAbsent(path, storePath);
        return raced != null ? raced : storePath;
    }

    /** References of any store path: ours from the registry, others from the daemon. */
    public SortedSet<String> referencesOf(String path) {
        Entry e = entries.get(path);
        if (e != null) return e.references();
        try {
            DaemonClient d = daemonOrNull();
            if (d != null) {
                DaemonClient.PathInfo info = d.queryPathInfo(path);
                if (info != null) return new TreeSet<>(info.references());
            }
        } catch (IOException ignored) {
            // unknown: no references
        }
        return new TreeSet<>();
    }

    /** References of a store path we created (empty for paths we don't know). */
    public SortedSet<String> references(String path) {
        Entry e = entries.get(path);
        return e == null ? new TreeSet<>() : e.references();
    }

    /**
     * Adds {@code root} and everything it references to the real store through the daemon,
     * dependencies first. The daemon computes each path itself; a mismatch with ours is an error.
     * Returns the number of paths that were not valid yet.
     */
    public int writeClosure(DaemonClient client, String root, Set<String> done) throws IOException {
        if (!done.add(root) || valid.contains(root)) return 0;
        Entry e = entries.get(root);
        if (e == null) return 0; // not created by us (e.g. builtins.storePath): must exist already
        int added = 0;
        for (String ref : e.references()) {
            if (!ref.equals(root)) added += writeClosure(client, ref, done);
        }
        if (client.isValidPath(root)) return added;
        String got = switch (e) {
            case Text t -> client.addToStore(t.name(), "text:sha256", t.references(), out -> out.write(Bytes.get(t.contents())));
            case Drv d -> client.addToStore(d.drv().name + ".drv", "text:sha256", d.references(), out -> out.write(Bytes.get(d.contents())));
            case Source src -> src.recursive()
                    ? client.addToStore(src.name(), "fixed:r:sha256", new TreeSet<>(), out -> Nar.dump(src.path(), src.filter(), out))
                    : client.addToStore(src.name(), "fixed:sha256", new TreeSet<>(), out -> Fs.readFile(src.path(), out));
        };
        if (!got.equals(root)) throw new IOException("store path mismatch: computed " + root + " but the daemon added " + got);
        return added + 1;
    }

    /** {@code computeFSClosure} over the paths we know about. */
    public TreeSet<String> closure(String path) {
        TreeSet<String> seen = new TreeSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>();
        todo.add(path);
        while (!todo.isEmpty()) {
            String p = todo.poll();
            if (seen.add(p)) todo.addAll(references(p));
        }
        return seen;
    }
}
