package nixtruffle.fetch;

import nixtruffle.fs.Fs;
import nixtruffle.runtime.Bytes;
import nixtruffle.store.Hash;
import nixtruffle.util.Json;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * nix-truffle's fetcher cache, in {@code $XDG_CACHE_HOME/nix-truffle/fetcher-cache-v1} (not
 * shared with Nix's). Like libfetchers' cache it maps a key (a type and some attributes, e.g. a
 * tarball URL) to attributes (etag, lastModified, ...) and optionally a store path, with a
 * timestamp for {@code tarball-ttl}. Each entry is a small JSON file, written atomically, so
 * concurrent evaluators can share the cache.
 */
public final class Cache {
    private final String dir;

    public Cache() {
        this.dir = cacheDir() + "/fetcher-cache-v1";
    }

    /** {@code $XDG_CACHE_HOME/nix-truffle} (default {@code ~/.cache/nix-truffle}), as a byte string. */
    public static String cacheDir() {
        String xdg = nixtruffle.util.Proc.getenv("XDG_CACHE_HOME");
        String base = xdg != null && !xdg.isEmpty() ? xdg : System.getProperty("user.home") + "/.cache";
        return Bytes.fromJava(base) + "/nix-truffle";
    }

    /** A cache entry: its attributes, store path (or null) and age. */
    public record Entry(Attrs value, String storePath, long timestamp) {
        public boolean expired(long ttl) {
            return System.currentTimeMillis() / 1000 > timestamp + ttl;
        }
    }

    private String file(String type, Attrs key) {
        String k = type + ":" + Attrs.Json.write(key);
        return dir + "/" + Hash.sha256(k).hex().substring(0, 40) + ".json";
    }

    public Entry lookup(String type, Attrs key) {
        try {
            String f = file(type, key);
            if (Fs.maybeLstat(f) == null) return null;
            Map<String, Object> json = Json.obj(Json.parse(Bytes.of(Fs.readFile(f))));
            // Guard against hash collisions: the key is stored too.
            if (!type.equals(json.get("type")) || !Attrs.Json.toJson(key).equals(json.get("key"))) return null;
            Object sp = json.get("storePath");
            return new Entry(Attrs.Json.fromJson(json.get("value")), sp instanceof String s ? s : null, (Long) json.get("timestamp"));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public void upsert(String type, Attrs key, Attrs value, String storePath) {
        TreeMap<String, Object> json = new TreeMap<>();
        json.put("type", type);
        json.put("key", Attrs.Json.toJson(key));
        json.put("value", Attrs.Json.toJson(value));
        if (storePath != null) json.put("storePath", storePath);
        json.put("timestamp", System.currentTimeMillis() / 1000);
        try {
            Fs.mkdirs(dir);
            String f = file(type, key);
            String tmp = f + ".tmp-" + ProcessHandle.current().pid() + "-" + Thread.currentThread().threadId();
            Fs.writeFile(tmp, Bytes.get(Json.write(json)), 0644);
            Fs.rename(tmp, f);
        } catch (IOException e) {
            // The cache is an optimisation; failing to write it isn't an error.
        }
    }

    public void upsert(String type, Attrs key, Attrs value) {
        upsert(type, key, value, null);
    }
}
