package nixtruffle.store;

import com.oracle.truffle.api.TruffleFile;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What evaluation has put "into the store": derivations, text files and copied sources, by store
 * path. Paths are computed exactly like Nix does, without touching the real store; {@link
 * #writeTo} can later add everything through the daemon.
 */
public final class Store {
    /** How to produce a store path's contents. */
    public sealed interface Entry {
        SortedSet<String> references();
    }

    public record Text(String name, String contents, SortedSet<String> references) implements Entry {}

    public record Source(String name, TruffleFile file, Nar.Filter filter, boolean recursive) implements Entry {
        public SortedSet<String> references() { return new TreeSet<>(); }
    }

    public record Drv(Derivation drv, String contents) implements Entry {
        public SortedSet<String> references() { return drv.references(); }
    }

    public final Map<String, Entry> entries = new HashMap<>();
    /** Output hashes modulo fixed-output derivations, per drv path and output (hex). */
    private final Map<String, Map<String, String>> drvHashes = new HashMap<>();
    /** {@code "${./foo}"}: local path -> store path. */
    private final Map<String, String> srcToStore = new HashMap<>();

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
        Map<String, String> h = drvHashes.get(drvPath);
        if (h == null) {
            h = hashModulo(derivation(drvPath), false);
            drvHashes.put(drvPath, h);
        }
        return h;
    }

    /** Writes a derivation "to the store": computes its path and remembers it. */
    public String addDerivation(Derivation drv) {
        String contents = drv.unparse(false, null);
        String path = StorePaths.textPath(drv.name + ".drv", contents, drv.references());
        entries.putIfAbsent(path, new Drv(drv, contents));
        drvHashes.computeIfAbsent(path, p -> hashModulo(drv, false));
        return path;
    }

    public String addText(String name, String contents, SortedSet<String> references) {
        String path = StorePaths.textPath(name, contents, references);
        entries.putIfAbsent(path, new Text(name, contents, references));
        return path;
    }

    /** Store path of a local file tree, as {@code builtins.path} / {@code "${./foo}"} would add it. */
    public String addSource(String name, TruffleFile file, Nar.Filter filter, boolean recursive) throws IOException {
        Hash hash = recursive ? Nar.hash(file, filter) : Hash.of("sha256", file.readAllBytes());
        String path = StorePaths.fixedOutputPath(recursive, hash, name);
        entries.putIfAbsent(path, new Source(name, file, filter, recursive));
        return path;
    }

    public String copyPathToStore(TruffleFile file) throws IOException {
        String key = file.getPath();
        String cached = srcToStore.get(key);
        if (cached != null) return cached;
        String path = addSource(file.getName(), file.getCanonicalFile(), null, true);
        srcToStore.put(key, path);
        return path;
    }

    /** References of a store path we created (empty for paths we don't know). */
    public SortedSet<String> references(String path) {
        Entry e = entries.get(path);
        return e == null ? new TreeSet<>() : e.references();
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
