package nixtruffle.fetch;

import nixtruffle.fs.Fs;
import nixtruffle.runtime.Bytes;
import nixtruffle.store.Hash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code file} and {@code tarball} inputs: things downloaded over HTTP(S), or local files. */
abstract class CurlScheme implements InputScheme {
    private static final Set<String> TRANSPORTS = Set.of("file", "http", "https");
    private static final Set<String> ALLOWED = Set.of("url", "narHash", "name", "unpack", "rev", "revCount", "lastModified");

    static boolean hasTarballExtension(Url url) {
        if (url.path.isEmpty()) return false;
        String p = url.path.get(url.path.size() - 1);
        return p.endsWith(".zip") || p.endsWith(".tar") || p.endsWith(".tgz") || p.endsWith(".tar.gz")
                || p.endsWith(".tar.xz") || p.endsWith(".tar.bz2") || p.endsWith(".tar.zst");
    }

    abstract boolean isValidUrl(Url url, boolean requireTree);

    @Override
    public Input inputFromURL(Fetcher f, Url url0, boolean requireTree) {
        if (!isValidUrl(url0, requireTree)) return null;
        Url url = url0.copy();
        url.scheme = Url.parseScheme(url.scheme)[1];
        Attrs attrs = new Attrs();
        String narHash = url.query.get("narHash");
        if (narHash != null) attrs.put("narHash", narHash);
        if (url.query.containsKey("rev")) attrs.put("rev", url.query.get("rev"));
        if (url.query.containsKey("revCount")) {
            Long n = PathScheme.parseUnsigned(url.query.get("revCount"));
            if (n != null) attrs.put("revCount", n);
        }
        if (url.query.containsKey("lastModified")) {
            Long n = PathScheme.parseUnsigned(url.query.get("lastModified"));
            if (n != null) attrs.put("lastModified", n);
        }
        for (String a : ALLOWED) url.query.remove(a);
        attrs.put("type", name());
        attrs.put("url", url.toString());
        return new Input(attrs, this);
    }

    boolean isValidUrlFor(Url url, boolean requireTree, boolean wantTree) {
        String[] s = Url.parseScheme(url.scheme);
        if (!TRANSPORTS.contains(s[1])) return false;
        if (s[0] != null) return s[0].equals(name());
        return wantTree ? requireTree || hasTarballExtension(url) : !requireTree && !hasTarballExtension(url);
    }

    @Override
    public Set<String> allowedAttrs() {
        return ALLOWED;
    }

    @Override
    public Input inputFromAttrs(Fetcher f, Attrs attrs) {
        return new Input(attrs, this);
    }

    @Override
    public Url toURL(Input input) {
        Url url = Url.parse(input.attrs.requireStr("url"), false);
        Hash narHash = input.getNarHash();
        if (narHash != null) url.query.put("narHash", narHash.sri());
        return url;
    }

    @Override
    public boolean isLocked(Fetcher f, Input input) {
        return input.getNarHash() != null;
    }

    // ----------------------------------------------------------- downloads

    record DownloadedFile(String storePath, String etag, String effectiveUrl, String immutableUrl) {}

    /** {@code downloadFile}: a URL's contents as a flat store path, cached for {@code tarball-ttl}. */
    static DownloadedFile downloadFile(Fetcher f, String url, String name, Map<String, String> headers) {
        Attrs key = Attrs.of("url", url, "name", name);
        Cache.Entry cached = f.cache.lookup("file", key);
        if (cached != null && (cached.storePath() == null || !f.isValid(cached.storePath()))) cached = null;
        if (cached != null && !cached.expired(f.tarballTtl())) {
            f.addTempRoot(cached.storePath());
            return new DownloadedFile(cached.storePath(), cached.value().getStr("etag"), cached.value().getStr("url"), cached.value().getStr("immutableUrl"));
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        Download.Result res;
        try {
            res = Download.download(url, headers, cached == null ? null : cached.value().getStr("etag"), data);
        } catch (FetchException e) {
            if (cached == null) throw e;
            System.err.println("warning: " + Bytes.toJava(e.getMessage()) + "; using cached version");
            return new DownloadedFile(cached.storePath(), cached.value().getStr("etag"), cached.value().getStr("url"), cached.value().getStr("immutableUrl"));
        }
        String storePath = res.notModified() ? cached.storePath() : f.addFlatFile(data.toByteArray(), name);
        Attrs info = Attrs.of("etag", res.etag(), "url", res.effectiveUrl());
        if (res.immutableUrl() != null) info.put("immutableUrl", res.immutableUrl());
        for (String u : res.urls()) f.cache.upsert("file", Attrs.of("url", u, "name", name), info, storePath);
        return new DownloadedFile(storePath, res.etag(), res.effectiveUrl(), res.immutableUrl());
    }

    record DownloadedTarball(String treePath, Hash narHash, long lastModified, String immutableUrl) {}

    /**
     * {@code downloadTarball_}: downloads and unpacks a tarball (a single top-level directory is
     * its root) into the store, cached for {@code tarball-ttl}. The tree is kept as a store path
     * named {@code source}.
     */
    static DownloadedTarball downloadTarball(Fetcher f, String url, Map<String, String> headers) {
        Url parsed = Url.parse(url, false);
        if (parsed.scheme.equals("file")) {
            String local = Url.urlPathToPath(parsed.path);
            if (!local.startsWith("/")) throw new FetchException("tarball '" + url + "' must use an absolute path. The 'file' scheme does not support relative paths.");
            try {
                Fs.Stat st = Fs.maybeStat(local);
                if (st == null) throw new FetchException("tarball '" + local + "' does not exist.");
                if (st.isDirectory()) {
                    if (Fs.maybeLstat(local + "/.git") != null) {
                        throw new FetchException("tarball '" + local + "' is a git repository, not a tarball. Please use `git+file` as the scheme.");
                    }
                    throw new FetchException("tarball '" + local + "' is a directory, not a file.");
                }
            } catch (IOException e) {
                throw new FetchException(e.getMessage());
            }
        }
        Attrs key = Attrs.of("url", url);
        Cache.Entry cached = f.cache.lookup("tarball", key);
        if (cached != null && (cached.storePath() == null || !f.isValid(cached.storePath()))) cached = null;
        if (cached != null && !cached.expired(f.tarballTtl())) return fromCache(f, cached);

        String tmp = Fetcher.tempDir("tarball");
        try {
            String file = tmp + "/download";
            Download.Result res;
            try (OutputStream out = java.nio.file.Files.newOutputStream(java.nio.file.Path.of(Bytes.toJava(file)))) {
                res = Download.download(url, headers, cached == null ? null : cached.value().getStr("etag"), out);
            }
            Attrs info;
            String storePath;
            if (res.notModified()) {
                info = cached.value();
                // Entries from before redirects' immutable links were recorded don't have one.
                if (res.immutableUrl() != null && info.getStr("immutableUrl") == null) {
                    info = info.copy();
                    info.put("immutableUrl", res.immutableUrl());
                }
                storePath = cached.storePath();
            } else {
                String root = tmp + "/unpacked";
                Fs.mkdir(root, 0755);
                long lastModified;
                try {
                    lastModified = Archive.unpack(file, root);
                } catch (IOException e) {
                    throw new FetchException("cannot unpack '" + url + "': " + e.getMessage());
                }
                String treeRoot = dereferenceSingletonDirectory(root);
                Fetcher.Tree tree = f.addTree(treeRoot, "source", null);
                storePath = tree.storePath();
                info = Attrs.of("etag", res.etag(), "narHash", tree.narHash().sri(), "lastModified", lastModified);
                if (res.immutableUrl() != null) info.put("immutableUrl", res.immutableUrl());
            }
            for (String u : res.urls()) f.cache.upsert("tarball", Attrs.of("url", u), info, storePath);
            return new DownloadedTarball(storePath, Hash.parseAny(info.getStr("narHash"), null), info.getInt("lastModified"), info.getStr("immutableUrl"));
        } catch (IOException e) {
            throw new FetchException("cannot download '" + url + "': " + e.getMessage());
        } finally {
            Fetcher.deleteTemp(tmp);
        }
    }

    private static DownloadedTarball fromCache(Fetcher f, Cache.Entry e) {
        f.addTempRoot(e.storePath());
        return new DownloadedTarball(e.storePath(), Hash.parseAny(e.value().getStr("narHash"), null),
                e.value().getInt("lastModified"), e.value().getStr("immutableUrl"));
    }

    /** A tree with a single entry that is a directory is replaced by that directory. */
    static String dereferenceSingletonDirectory(String root) throws IOException {
        List<String> entries = Fs.list(root);
        if (entries.size() == 1) {
            String only = root + "/" + entries.get(0);
            if (Fs.lstat(only).isDirectory()) return only;
        }
        return root;
    }

    // --------------------------------------------------------------- file

    static final class File extends CurlScheme {
        @Override
        public String name() {
            return "file";
        }

        @Override
        boolean isValidUrl(Url url, boolean requireTree) {
            return isValidUrlFor(url, requireTree, false);
        }

        @Override
        public Result fetch(Fetcher f, Input input0) {
            Input input = input0.withAttrs(input0.attrs.copy());
            DownloadedFile file = downloadFile(f, input.attrs.requireStr("url"), input.getName(), Map.of());
            // The tree is the file itself, NAR-hashed.
            return new Result(f.addTree(file.storePath(), input.getName(), null), input);
        }
    }

    // ------------------------------------------------------------ tarball

    static final class Tarball extends CurlScheme {
        @Override
        public String name() {
            return "tarball";
        }

        @Override
        boolean isValidUrl(Url url, boolean requireTree) {
            return isValidUrlFor(url, requireTree, true);
        }

        @Override
        public Result fetch(Fetcher f, Input input0) {
            Input input = input0.withAttrs(input0.attrs.copy());
            DownloadedTarball t = downloadTarball(f, input.attrs.requireStr("url"), Map.of());
            if (t.immutableUrl() != null) {
                Input immutable = Input.fromURL(f, t.immutableUrl(), false);
                if (!immutable.getType().equals("tarball")) {
                    throw new FetchException("tarball 'Link' headers that redirect to non-tarball URLs are not supported");
                }
                input = immutable.withAttrs(immutable.attrs.copy());
            }
            if (t.lastModified() != 0 && !input.attrs.containsKey("lastModified")) input.attrs.put("lastModified", t.lastModified());
            return new Result(f.addHashedTree(t.treePath(), input.getName(), t.narHash()), input);
        }
    }
}
