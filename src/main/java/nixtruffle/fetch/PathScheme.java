package nixtruffle.fetch;

import nixtruffle.fs.Fs;
import nixtruffle.runtime.NixPath;
import nixtruffle.store.StorePaths;

import java.io.IOException;
import java.util.Set;
import java.util.TreeMap;

/** {@code path} inputs: a local directory, copied to the store as {@code source}. */
final class PathScheme implements InputScheme {
    @Override
    public String name() {
        return "path";
    }

    @Override
    public boolean needsFlakes() {
        return true;
    }

    /** {@code string2Int<uint64_t>}: plain decimal digits. */
    static Long parseUnsigned(String s) {
        if (s.isEmpty() || !s.chars().allMatch(c -> c >= '0' && c <= '9')) return null;
        try {
            long v = Long.parseUnsignedLong(s);
            return v < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public Input inputFromURL(Fetcher f, Url url, boolean requireTree) {
        if (!url.scheme.equals("path")) return null;
        if (url.authority != null && !url.authority.host().isEmpty()) {
            throw new FetchException("path URL '" + url + "' should not have an authority ('" + url.authority + "')");
        }
        Attrs attrs = Attrs.of("type", "path", "path", Url.urlPathToPath(url.path));
        for (var e : url.query.entrySet()) {
            String name = e.getKey();
            if (name.equals("rev") || name.equals("narHash")) {
                attrs.put(name, e.getValue());
            } else if (name.equals("revCount") || name.equals("lastModified")) {
                Long n = parseUnsigned(e.getValue());
                if (n == null) throw new FetchException("path URL '" + url + "' has invalid parameter '" + name + "'");
                attrs.put(name, n);
            } else {
                throw new FetchException("path URL '" + url + "' has unsupported parameter '" + name + "'");
            }
        }
        return new Input(attrs, this);
    }

    @Override
    public Set<String> allowedAttrs() {
        return Set.of("path", "rev", "revCount", "lastModified", "narHash");
    }

    @Override
    public Input inputFromAttrs(Fetcher f, Attrs attrs) {
        attrs.requireStr("path");
        return new Input(attrs, this);
    }

    @Override
    public Url toURL(Input input) {
        TreeMap<String, String> query = input.attrs.toQuery();
        query.remove("path");
        query.remove("type");
        query.remove("__final");
        return new Url("path", null, Url.pathToUrlPath(input.attrs.requireStr("path")), query, null);
    }

    @Override
    public String getSourcePath(Input input) {
        return absPath(input);
    }

    @Override
    public String isRelative(Input input) {
        String p = input.attrs.requireStr("path");
        return p.startsWith("/") ? null : p;
    }

    @Override
    public boolean isLocked(Fetcher f, Input input) {
        return input.getNarHash() != null;
    }

    @Override
    public void putFile(Input input, String relPath, byte[] contents) {
        String path = absPath(input) + "/" + relPath;
        try {
            Fs.writeFile(path, contents, 0666);
        } catch (IOException e) {
            throw new FetchException("cannot write '" + path + "': " + e.getMessage());
        }
    }

    static String absPath(Input input) {
        String p = input.attrs.requireStr("path");
        if (p.startsWith("/")) return NixPath.canonicalize(p);
        throw new FetchException("cannot fetch input '" + input + "' because it uses a relative path");
    }

    @Override
    public Result fetch(Fetcher f, Input input0) {
        Input input = input0.withAttrs(input0.attrs.copy());
        String path = absPath(input);
        long mtime = 0;
        Fetcher.Tree tree;
        String storePath = StorePaths.parseStorePath(path);
        if (storePath != null && storePath.equals(path) && storePath.endsWith("-source") && f.isValid(storePath)) {
            // Already a source in the store: use it as it is.
            f.addTempRoot(storePath);
            tree = f.addTree(path, "source", null);
        } else {
            try {
                if (Fs.maybeLstat(path) == null) throw new FetchException("path '" + path + "' does not exist");
                mtime = lastModified(path);
            } catch (IOException e) {
                throw new FetchException(e.getMessage());
            }
            tree = f.addTree(path, "source", null);
        }
        // Trust the lastModified value supplied by the user, if any.
        if (input.getLastModified() == null) input.attrs.put("lastModified", mtime);
        return new Result(tree, input);
    }

    /** The newest mtime of anything in the tree (CppNix's {@code dumpPathAndGetMtime}). */
    static long lastModified(String path) throws IOException {
        Fs.Stat st = Fs.lstat(path);
        long max = st.mtime();
        if (st.isDirectory()) {
            for (String name : Fs.list(path)) max = Math.max(max, lastModified(path + "/" + name));
        }
        return max;
    }
}
