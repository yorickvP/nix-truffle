package nixtruffle.fetch;

import nixtruffle.store.Hash;
import nixtruffle.store.StorePaths;

import java.util.List;
import java.util.TreeMap;

/**
 * Something that can be fetched: attributes plus the scheme that interprets them (a port of
 * libfetchers' {@code Input}). An input with an unknown {@code type} has no scheme; most things
 * fail on it, but it can still be printed as attributes.
 */
public final class Input {
    public Attrs attrs;
    final InputScheme scheme;

    Input(Attrs attrs, InputScheme scheme) {
        this.attrs = attrs;
        this.scheme = scheme;
    }

    /** Schemes in name order, which is the order {@code fromURL} tries them in. */
    static final List<InputScheme> SCHEMES = List.of(new CurlScheme.File(), new GitScheme(), new GitArchiveScheme.GitHub(),
            new GitArchiveScheme.GitLab(), new MercurialScheme(), new IndirectScheme(), new PathScheme(),
            new GitArchiveScheme.SourceHut(), new CurlScheme.Tarball());

    public Input withAttrs(Attrs a) {
        return new Input(a, scheme);
    }

    private static void fixup(Input input) {
        // Check the common attributes' types.
        input.getType();
        input.getRef();
        input.getRevCount();
        input.getLastModified();
    }

    private static void requireFeature(Fetcher f, InputScheme scheme) {
        if (scheme.needsFlakes() && !f.flakesEnabled()) {
            throw new FetchException("experimental Nix feature 'flakes' is disabled; add '--extra-experimental-features flakes' to enable it");
        }
    }

    public static Input fromURL(Fetcher f, String url, boolean requireTree) {
        return fromURL(f, Url.parse(url, false), requireTree);
    }

    public static Input fromURL(Fetcher f, Url url, boolean requireTree) {
        for (InputScheme scheme : SCHEMES) {
            Input res = scheme.inputFromURL(f, url, requireTree);
            if (res != null) {
                requireFeature(f, scheme);
                Input input = new Input(res.attrs, scheme);
                fixup(input);
                return input;
            }
        }
        String[] s = Url.parseScheme(url.scheme);
        if ("file".equals(s[0]) && "git".equals(s[1])) {
            throw new FetchException("input '" + url + "' is unsupported; did you mean 'git+file' instead of 'file+git'?");
        }
        throw new FetchException("input '" + url + "' is unsupported");
    }

    public static Input fromAttrs(Fetcher f, Attrs attrs) {
        String type = attrs.getStr("type");
        if (type == null) throw new FetchException("'type' attribute to specify input scheme is required but not provided");
        InputScheme scheme = null;
        for (InputScheme s : SCHEMES) if (s.name().equals(type)) scheme = s;
        if (scheme == null) {
            Input raw = new Input(attrs.copy(), null);
            fixup(raw);
            return raw;
        }
        requireFeature(f, scheme);
        for (String name : attrs.keySet()) {
            if (!name.equals("type") && !name.equals("__final") && !scheme.allowedAttrs().contains(name)) {
                throw new FetchException("input attribute '" + name + "' not supported by scheme '" + type + "'");
            }
        }
        Input res = scheme.inputFromAttrs(f, attrs.copy());
        if (res == null) {
            Input raw = new Input(attrs.copy(), null);
            fixup(raw);
            return raw;
        }
        Input input = new Input(res.attrs, scheme);
        fixup(input);
        return input;
    }

    // ------------------------------------------------------------ printing

    public Url toURL() {
        if (scheme == null) throw new FetchException("cannot show unsupported input '" + Attrs.Json.write(attrs) + "'");
        return scheme.toURL(this);
    }

    public String toURLString(TreeMap<String, String> extraQuery) {
        Url url = toURL();
        if (extraQuery != null) url.query.putAll(extraQuery);
        return url.toString();
    }

    @Override
    public String toString() {
        return toURL().toString();
    }

    // ------------------------------------------------------------- queries

    public boolean isDirect() {
        return scheme == null || scheme.isDirect(this);
    }

    public boolean isLocked(Fetcher f) {
        return scheme != null && scheme.isLocked(f, this);
    }

    public boolean isFinal() {
        return attrs.getBool("__final", false);
    }

    public String isRelative() {
        return scheme == null ? null : scheme.isRelative(this);
    }

    public String getSourcePath() {
        return scheme == null ? null : scheme.getSourcePath(this);
    }

    public void putFile(String relPath, byte[] contents, String commitMessage) {
        if (scheme == null) throw new FetchException("input '" + Attrs.Json.write(attrs) + "' does not support modifying file '/" + relPath + "'");
        scheme.putFile(this, relPath, contents, commitMessage);
    }

    public String getName() {
        String n = attrs.getStr("name");
        return n == null ? "source" : n;
    }

    public String getType() {
        return attrs.requireStr("type");
    }

    public Hash getNarHash() {
        String s = attrs.getStr("narHash");
        if (s == null) return null;
        Hash h;
        try {
            h = s.isEmpty() ? new Hash("sha256", new byte[32]) : parseSri(s);
        } catch (IllegalArgumentException e) {
            throw new FetchException(e.getMessage());
        }
        if (!h.algo().equals("sha256")) throw new FetchException("narHash must use SHA-256");
        return h;
    }

    private static Hash parseSri(String s) {
        int dash = s.indexOf('-');
        if (dash < 0) throw new IllegalArgumentException("hash '" + s + "' is not SRI");
        return Hash.parseAny(s, null);
    }

    public String getRef() {
        return attrs.getStr("ref");
    }

    /** The revision, as 40 hex digits (SHA-1) or prefixed; null if there's none. */
    public String getRev() {
        String s = attrs.getStr("rev");
        if (s == null) return null;
        try {
            Hash h;
            try {
                h = Hash.parseAnyPrefixed(s);
            } catch (IllegalArgumentException e) {
                h = Hash.parseAny(s, "sha1");
            }
            return h.hex();
        } catch (IllegalArgumentException e) {
            throw new FetchException("invalid Git revision '" + s + "'");
        }
    }

    public Long getRevCount() {
        return attrs.getInt("revCount");
    }

    public Long getLastModified() {
        return attrs.getInt("lastModified");
    }

    public boolean sameAs(Input other) {
        return attrs.sameAs(other.attrs);
    }

    /** {@code Input::contains}: equal, or equal once {@code other}'s ref and rev are dropped. */
    public boolean contains(Input other) {
        if (sameAs(other)) return true;
        Attrs a = other.attrs.copy();
        a.remove("ref");
        a.remove("rev");
        return attrs.sameAs(a);
    }

    public Input applyOverrides(String ref, String rev) {
        return scheme == null ? this : scheme.applyOverrides(this, ref, rev);
    }

    public String computeStorePath() {
        Hash narHash = getNarHash();
        if (narHash == null) throw new FetchException("cannot compute store path for unlocked input '" + this + "'");
        return StorePaths.fixedOutputPath(true, narHash, getName());
    }

    // ------------------------------------------------------------ fetching

    /**
     * {@code getAccessor} and {@code mountInput}: fetches the input into the store (or finds it
     * there), returning the store path and the locked input, which has a {@code narHash}.
     */
    public Fetcher.Fetched fetch(Fetcher f) {
        if (scheme == null) throw new FetchException("cannot fetch unsupported input '" + Attrs.Json.write(attrs) + "'");
        Fetcher.Fetched cached = f.cachedFetch(this);
        if (cached != null) return cached;
        Fetcher.Fetched res;
        try {
            res = fetchUncached(f);
        } catch (FetchException e) {
            throw new FetchException(e.getMessage() + "\n       … while fetching the input '" + this + "'", e);
        }
        f.cacheFetch(this, res);
        return res;
    }

    private Fetcher.Fetched fetchUncached(Fetcher f) {
        Hash expected = getNarHash();
        // A final input with a NAR hash may already be in the store.
        if (isFinal() && expected != null) {
            String storePath = computeStorePath();
            if (f.isValid(storePath)) {
                f.addTempRoot(storePath);
                return new Fetcher.Fetched(storePath, this);
            }
        }
        InputScheme.Result r = scheme.fetch(f, this);
        Input result = r.input();
        result.attrs.put("__final", true);
        checkLocks(this, result);
        String narHash = r.tree().narHash().sri();
        if (expected != null && !r.tree().narHash().equals(expected)) {
            throw new FetchException("NAR hash mismatch in input '" + this + "', expected '" + expected.sri() + "' but got '" + narHash + "'");
        }
        result.attrs.put("narHash", narHash);
        return new Fetcher.Fetched(r.tree().storePath(), result);
    }

    /** {@code Input::checkLocks}: the fetched input must agree with what was asked for. */
    static void checkLocks(Input specified, Input result) {
        if (specified.isFinal()) {
            Attrs spec = specified.attrs.copy();
            Hash prev = specified.getNarHash();
            if (prev != null) spec.put("narHash", prev.sri());
            Hash got = result.getNarHash();
            if (got != null) result.attrs.put("narHash", got.sri());
            for (var e : spec.entrySet()) {
                Object v2 = result.attrs.get(e.getKey());
                if (v2 != null && !resolve(v2).equals(resolve(e.getValue()))) {
                    throw new FetchException("mismatch in field '" + e.getKey() + "' of input '" + Attrs.Json.write(spec) + "', got '" + Attrs.Json.write(result.attrs) + "'");
                }
            }
            result.attrs = spec;
            return;
        }
        Hash prevNarHash = specified.getNarHash();
        if (prevNarHash != null && result.getNarHash() != null && !prevNarHash.equals(result.getNarHash())) {
            throw new FetchException("NAR hash mismatch in input '" + specified + "', expected '" + prevNarHash.sri() + "' but got '" + result.getNarHash().sri() + "'");
        }
        String prevRev = specified.getRev();
        if (prevRev != null && !prevRev.equals(result.getRev())) {
            throw new FetchException("'rev' attribute mismatch in input '" + result + "', expected " + prevRev);
        }
    }

    private static Object resolve(Object v) {
        return v instanceof Attrs.Lazy l ? l.get() : v;
    }
}
