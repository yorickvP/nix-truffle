package nixtruffle.fetch;

import nixtruffle.runtime.Bytes;
import nixtruffle.store.Hash;
import nixtruffle.util.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** {@code github}, {@code gitlab} and {@code sourcehut} inputs: tarballs of commits from a forge. */
abstract class GitArchiveScheme implements InputScheme {
    private static final Pattern HOST = Pattern.compile("[a-zA-Z0-9.-]*");
    static final Pattern REV = Pattern.compile("[0-9a-fA-F]{40}");

    @Override
    public boolean needsFlakes() {
        return true;
    }

    @Override
    public Input inputFromURL(Fetcher f, Url url, boolean requireTree) {
        if (!url.scheme.equals(name())) return null;
        List<String> path = url.pathSegments(true);
        Attrs attrs = new Attrs();
        int size = path.size();
        if (size == 3) {
            if (REV.matcher(path.get(2)).matches()) attrs.put("rev", path.get(2)); else attrs.put("ref", path.get(2));
        } else if (size > 3) {
            attrs.put("ref", String.join("/", path.subList(2, size)));
        } else if (size < 2) {
            throw new FetchException.BadUrl("URL '" + url + "' is invalid");
        }
        for (var e : url.query.entrySet()) {
            String name = e.getKey();
            switch (name) {
                case "rev" -> {
                    if (attrs.containsKey(name)) throw new FetchException.BadUrl("URL '" + url + "' contains multiple commit hashes");
                    attrs.put("rev", e.getValue());
                }
                case "ref" -> {
                    if (attrs.containsKey(name)) throw new FetchException.BadUrl("URL '" + url + "' contains multiple branch/tag names");
                    attrs.put("ref", e.getValue());
                }
                case "host", "narHash" -> attrs.put(name, e.getValue());
                default -> throw new FetchException.BadUrl("URL '" + url + "' contains unknown parameter '" + name + "'");
            }
        }
        attrs.put("type", name());
        attrs.put("owner", path.get(0));
        attrs.put("repo", path.get(1));
        return inputFromAttrs(f, attrs);
    }

    @Override
    public Set<String> allowedAttrs() {
        return Set.of("owner", "repo", "ref", "rev", "narHash", "lastModified", "host", "treeHash");
    }

    @Override
    public Input inputFromAttrs(Fetcher f, Attrs attrs) {
        attrs.requireStr("owner");
        attrs.requireStr("repo");
        String ref = attrs.getStr("ref");
        String rev = attrs.getStr("rev");
        if (ref != null && rev != null) {
            throw new FetchException.BadUrl("input " + Attrs.Json.write(attrs) + " contains both a commit hash ('" + rev + "') and a branch/tag name ('" + ref + "')");
        }
        if (rev != null) {
            try {
                Hash.parseAny(rev, "sha1");
            } catch (IllegalArgumentException e) {
                throw new FetchException(e.getMessage());
            }
        }
        if (ref != null && !RefNames.isLegal(ref)) throw new FetchException.BadUrl("input " + Attrs.Json.write(attrs) + " contains an invalid branch/tag name");
        String host = attrs.getStr("host");
        if (host != null && !HOST.matcher(host).matches()) throw new FetchException.BadUrl("input " + Attrs.Json.write(attrs) + " contains an invalid instance host");
        return new Input(attrs, this);
    }

    @Override
    public Url toURL(Input input) {
        List<String> path = new java.util.ArrayList<>(List.of(input.attrs.requireStr("owner"), input.attrs.requireStr("repo")));
        String ref = input.getRef();
        String rev = input.getRev();
        if (ref != null) path.add(ref);
        if (rev != null) path.add(rev);
        Url url = new Url(name(), null, path, null, null);
        Hash narHash = input.getNarHash();
        if (narHash != null) url.query.put("narHash", narHash.sri());
        String host = input.attrs.getStr("host");
        if (host != null) url.query.put("host", host);
        return url;
    }

    @Override
    public Input applyOverrides(Input input, String ref, String rev) {
        if (rev != null && ref != null) {
            throw new FetchException.BadUrl("cannot apply both a commit hash (" + rev + ") and a branch/tag name ('" + ref + "') to input '" + input + "'");
        }
        Attrs a = input.attrs.copy();
        if (rev != null) {
            a.put("rev", rev);
            a.remove("ref");
        }
        if (ref != null) {
            a.put("ref", ref);
            a.remove("rev");
        }
        return input.withAttrs(a);
    }

    @Override
    public boolean isLocked(Fetcher f, Input input) {
        return input.getRev() != null && input.getNarHash() != null;
    }

    abstract String defaultHost();

    String host(Input input) {
        String h = input.attrs.getStr("host");
        return h == null ? defaultHost() : h;
    }

    /** The authorization header for an access token. */
    abstract Map.Entry<String, String> accessHeader(String token);

    /** {@code access-tokens}: the longest matching host/path prefix, else the host. */
    Map<String, String> headers(Fetcher f, Input input) {
        String host = host(input);
        String hostAndPath = host + "/" + input.attrs.requireStr("owner") + "/" + input.attrs.requireStr("repo");
        Map<String, String> tokens = new LinkedHashMap<>();
        for (var e : f.settings.accessTokens().entrySet()) tokens.put(Bytes.fromJava(e.getKey()), Bytes.fromJava(e.getValue()));
        String answer = null;
        int best = 0;
        for (var t : tokens.entrySet()) {
            String k = t.getKey();
            if (hostAndPath.startsWith(k) && k.length() > best && (hostAndPath.length() == k.length() || hostAndPath.charAt(k.length()) == '/')) {
                answer = t.getValue();
                best = k.length();
            }
        }
        if (answer == null) answer = tokens.get(host);
        Map<String, String> headers = new LinkedHashMap<>();
        if (answer != null) {
            Map.Entry<String, String> h = accessHeader(answer);
            headers.put(h.getKey(), h.getValue());
        }
        return headers;
    }

    abstract String revFromRef(Fetcher f, Input input);

    abstract String downloadUrl(Input input, Map<String, String> headers);

    @Override
    public Result fetch(Fetcher f, Input input0) {
        Input input = input0.withAttrs(input0.attrs.copy());
        if (input.attrs.getStr("ref") == null) input.attrs.put("ref", "HEAD");
        String rev = input.getRev();
        if (rev == null) rev = revFromRef(f, input);
        input.attrs.remove("ref");
        input.attrs.put("rev", rev);
        Attrs key = Attrs.of("rev", rev);
        Cache.Entry cached = f.cache.lookup("gitArchiveRev", key);
        String treePath;
        Hash narHash;
        long lastModified;
        if (cached != null && cached.storePath() != null && f.isValid(cached.storePath())) {
            treePath = cached.storePath();
            narHash = Hash.parseAny(cached.value().getStr("narHash"), null);
            lastModified = cached.value().getInt("lastModified");
        } else {
            Map<String, String> headers = headers(f, input);
            CurlScheme.DownloadedTarball t = CurlScheme.downloadTarball(f, downloadUrl(input, headers), headers);
            treePath = t.treePath();
            narHash = t.narHash();
            lastModified = t.lastModified();
            f.cache.upsert("gitArchiveRev", key, Attrs.of("narHash", narHash.sri(), "lastModified", lastModified), treePath);
        }
        input.attrs.put("lastModified", lastModified);
        return new Result(f.addHashedTree(treePath, input.getName(), narHash), input);
    }

    /** Downloads JSON (API responses), cached for {@code tarball-ttl}. */
    static Object downloadJson(Fetcher f, String url, Map<String, String> headers) {
        CurlScheme.DownloadedFile file = CurlScheme.downloadFile(f, url, "source", headers);
        try {
            return Json.parse(Bytes.of(nixtruffle.fs.Fs.readFile(file.storePath())));
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new FetchException("cannot read the response from '" + url + "': " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- github

    static final class GitHub extends GitArchiveScheme {
        @Override
        public String name() {
            return "github";
        }

        @Override
        String defaultHost() {
            return "github.com";
        }

        @Override
        Map.Entry<String, String> accessHeader(String token) {
            return Map.entry("Authorization", "token " + token);
        }

        @Override
        String revFromRef(Fetcher f, Input input) {
            String host = host(input);
            String url = (host.equals("github.com") ? "https://api." + host + "/repos/" : "https://" + host + "/api/v3/repos/")
                    + input.attrs.requireStr("owner") + "/" + input.attrs.requireStr("repo") + "/commits/" + input.getRef();
            Map<String, Object> json = Json.obj(downloadJson(f, url, headers(f, input)));
            return Json.str(json.get("sha")).toLowerCase();
        }

        @Override
        String downloadUrl(Input input, Map<String, String> headers) {
            String host = host(input);
            String owner = input.attrs.requireStr("owner");
            String repo = input.attrs.requireStr("repo");
            String rev = input.getRev();
            if (!host.equals("github.com")) return "https://" + host + "/api/v3/repos/" + owner + "/" + repo + "/tarball/" + rev;
            if (headers.isEmpty()) return "https://" + host + "/" + owner + "/" + repo + "/archive/" + rev + ".tar.gz";
            return "https://api." + host + "/repos/" + owner + "/" + repo + "/tarball/" + rev;
        }
    }

    // ------------------------------------------------------------- gitlab

    static final class GitLab extends GitArchiveScheme {
        @Override
        public String name() {
            return "gitlab";
        }

        @Override
        String defaultHost() {
            return "gitlab.com";
        }

        @Override
        Map.Entry<String, String> accessHeader(String token) {
            int colon = token.indexOf(':');
            String type = colon < 0 ? token : token.substring(0, colon);
            String value = colon < 0 ? "" : token.substring(colon + 1);
            if (type.equals("OAuth2")) return Map.entry("Authorization", "Bearer " + value);
            if (type.equals("PAT")) return Map.entry("Private-token", value);
            return Map.entry(type, value);
        }

        @Override
        String revFromRef(Fetcher f, Input input) {
            String url = "https://" + host(input) + "/api/v4/projects/" + input.attrs.requireStr("owner") + "%2F"
                    + input.attrs.requireStr("repo") + "/repository/commits?ref_name=" + input.getRef();
            Object json = downloadJson(f, url, headers(f, input));
            List<Object> commits = Json.arr(json);
            if (commits.isEmpty()) throw new FetchException("No commits returned by GitLab API -- does the git ref really exist?");
            return Json.str(Json.obj(commits.get(0)).get("id"));
        }

        @Override
        String downloadUrl(Input input, Map<String, String> headers) {
            return "https://" + host(input) + "/api/v4/projects/" + input.attrs.requireStr("owner") + "%2F"
                    + input.attrs.requireStr("repo") + "/repository/archive.tar.gz?sha=" + input.getRev();
        }
    }

    // ---------------------------------------------------------- sourcehut

    static final class SourceHut extends GitArchiveScheme {
        @Override
        public String name() {
            return "sourcehut";
        }

        @Override
        String defaultHost() {
            return "git.sr.ht";
        }

        @Override
        Map.Entry<String, String> accessHeader(String token) {
            return Map.entry("Authorization", "Bearer " + token);
        }

        @Override
        String revFromRef(Fetcher f, Input input) {
            String ref = input.getRef();
            String base = "https://" + host(input) + "/" + input.attrs.requireStr("owner") + "/" + input.attrs.requireStr("repo");
            Map<String, String> headers = headers(f, input);
            String refPattern;
            if (ref.equals("HEAD")) {
                String head = readText(f, base + "/HEAD", headers).split("\n")[0].strip();
                if (!head.startsWith("ref: ")) throw new FetchException.BadUrl("in '" + input + "', couldn't resolve HEAD ref '" + ref + "'");
                refPattern = Pattern.quote(head.substring(5).strip());
            } else {
                refPattern = "refs/(heads|tags)/" + Pattern.quote(ref);
            }
            Pattern p = Pattern.compile(refPattern);
            for (String line : readText(f, base + "/info/refs", headers).split("\n")) {
                String[] parts = line.split("\t");
                if (parts.length == 2 && p.matcher(parts[1]).matches()) return parts[0];
            }
            throw new FetchException.BadUrl("in '" + input + "', couldn't find ref '" + ref + "'");
        }

        private static String readText(Fetcher f, String url, Map<String, String> headers) {
            CurlScheme.DownloadedFile file = CurlScheme.downloadFile(f, url, "source", headers);
            try {
                return Bytes.of(nixtruffle.fs.Fs.readFile(file.storePath()));
            } catch (java.io.IOException e) {
                throw new FetchException(e.getMessage());
            }
        }

        @Override
        String downloadUrl(Input input, Map<String, String> headers) {
            return "https://" + host(input) + "/" + input.attrs.requireStr("owner") + "/" + input.attrs.requireStr("repo")
                    + "/archive/" + input.getRev() + ".tar.gz";
        }
    }
}
