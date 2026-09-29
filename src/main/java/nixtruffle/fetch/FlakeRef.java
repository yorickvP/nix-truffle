package nixtruffle.fetch;

import nixtruffle.fs.Fs;
import nixtruffle.runtime.NixPath;

import java.io.IOException;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A flake reference: an input and a subdirectory ({@code libflake/flakeref.cc}). */
public final class FlakeRef {
    public final Input input;
    public final String subdir;

    public FlakeRef(Input input, String subdir) {
        this.input = input;
        this.subdir = subdir;
    }

    @Override
    public String toString() {
        TreeMap<String, String> extra = new TreeMap<>();
        if (!subdir.isEmpty()) extra.put("dir", subdir);
        return input.toURLString(extra);
    }

    public Attrs toAttrs() {
        Attrs a = input.attrs.copy();
        if (!subdir.isEmpty()) a.put("dir", subdir);
        return a;
    }

    public static FlakeRef fromAttrs(Fetcher f, Attrs attrs) {
        Attrs a = attrs.copy();
        Object dir = a.remove("dir");
        String subdir = dir == null ? "" : attrs.getStr("dir");
        return new FlakeRef(Input.fromAttrs(f, a), subdir);
    }

    public boolean sameAs(FlakeRef other) {
        return input.sameAs(other.input) && subdir.equals(other.subdir);
    }

    /** Looks the input up in the registries; the registry entry's {@code dir} wins. */
    public FlakeRef resolve(Fetcher f, Registry.Use use) {
        Object[] r = Registry.lookup(f, input, use);
        String dir = ((Attrs) r[1]).getStr("dir");
        return new FlakeRef((Input) r[0], dir != null ? dir : subdir);
    }

    /** {@code canonicalize}: a {@code dir} query parameter in the URL that repeats the subdirectory is dropped. */
    public FlakeRef canonicalize() {
        String url = input.attrs.getStr("url");
        if (url == null) return this;
        try {
            Url parsed = Url.parse(url, true);
            String dir2 = parsed.query.get("dir");
            if (dir2 != null && !subdir.isEmpty() && subdir.equals(dir2)) parsed.query.remove("dir");
            Attrs a = input.attrs.copy();
            a.put("url", parsed.toString());
            return new FlakeRef(input.withAttrs(a), subdir);
        } catch (FetchException.BadUrl e) {
            return this;
        }
    }

    // ------------------------------------------------------------- parsing

    private static final String UNRESERVED = "(?:[a-zA-Z0-9\\-._~])";
    private static final String PCT = "(?:%[0-9a-fA-F][0-9a-fA-F])";
    private static final String SUBDELIMS = "(?:[!$&'\"()*+,;=])";
    private static final String PCHAR = "(?:" + UNRESERVED + "|" + PCT + "|" + SUBDELIMS + "|[:@])";
    private static final String FRAGMENT = "(?:" + PCHAR + "|[/? \"^])*";
    private static final String REF = "[a-zA-Z0-9@][a-zA-Z0-9_.\\/@+-]*";
    private static final String REV = "[0-9a-fA-F]{40}";
    private static final String FLAKE_ID = "[a-zA-Z][a-zA-Z0-9_-]*";
    private static final Pattern FLAKE_ID_REF = Pattern.compile(
            "((" + FLAKE_ID + ")(?:/(?:(" + REV + ")|(?:(" + REF + ")(?:/(" + REV + "))?)))?)(?:#(" + FRAGMENT + "))?");
    private static final Pattern PATH_REF = Pattern.compile("([^?#]*)(\\?([^#]*))?(#(.*))?", Pattern.DOTALL);

    /** A flake reference and a fragment. */
    public record WithFragment(FlakeRef ref, String fragment) {}

    /** {@code parseFlakeRef}: a fragment is an error. */
    public static FlakeRef parse(Fetcher f, String url, String baseDir, boolean allowMissing, boolean isFlake, boolean preserveRelativePaths) {
        WithFragment r = parseWithFragment(f, url, baseDir, allowMissing, isFlake, preserveRelativePaths);
        if (!r.fragment.isEmpty()) throw new FetchException("unexpected fragment '" + r.fragment + "' in flake reference '" + url + "'");
        return r.ref;
    }

    public static WithFragment parseWithFragment(Fetcher f, String url, String baseDir, boolean allowMissing, boolean isFlake, boolean preserveRelativePaths) {
        Matcher m = FLAKE_ID_REF.matcher(url);
        if (m.matches()) {
            Url parsed = new Url("flake", null, List.of(m.group(1).split("/", -1)), null, null);
            return new WithFragment(new FlakeRef(Input.fromURL(f, parsed, isFlake), ""), Url.percentDecode(m.group(6) == null ? "" : m.group(6)));
        }
        WithFragment r = parseUrlFlakeRef(f, url, baseDir, isFlake);
        if (r != null) return r;
        return parsePathFlakeRef(f, url, baseDir, allowMissing, isFlake, preserveRelativePaths);
    }

    private static WithFragment fromParsedUrl(Fetcher f, Url url, boolean isFlake) {
        String dir = url.query.remove("dir");
        String fragment = url.fragment;
        url.fragment = "";
        return new WithFragment(new FlakeRef(Input.fromURL(f, url, isFlake), dir == null ? "" : dir), fragment);
    }

    private static WithFragment parseUrlFlakeRef(Fetcher f, String url, String baseDir, boolean isFlake) {
        try {
            Url parsed = Url.parse(url, true);
            if (baseDir != null && (parsed.scheme.equals("path") || parsed.scheme.equals("git+file"))) {
                String path = Url.urlPathToPath(parsed.path);
                if (!path.startsWith("/")) parsed.path = Url.pathToUrlPath(NixPath.canonicalize(baseDir + "/" + path));
            }
            return fromParsedUrl(f, parsed, isFlake);
        } catch (FetchException.BadUrl e) {
            return null;
        }
    }

    private static WithFragment parsePathFlakeRef(Fetcher f, String url, String baseDir, boolean allowMissing, boolean isFlake, boolean preserveRelativePaths) {
        Matcher m = PATH_REF.matcher(url);
        if (!m.matches()) throw new FetchException("invalid flakeref '" + url + "'");
        String path = m.group(1);
        TreeMap<String, String> query = Url.decodeQuery(m.group(3) == null ? "" : m.group(3), true);
        String fragment = Url.percentDecode(m.group(5) == null ? "" : m.group(5));
        if (baseDir != null) {
            path = NixPath.canonicalize(path.startsWith("/") ? path : baseDir + "/" + path);
            if (isFlake) {
                try {
                    Fs.Stat st = Fs.lstat(path);
                    if (!st.isDirectory()) {
                        if (path.endsWith("/flake.nix")) {
                            path = path.substring(0, path.length() - "/flake.nix".length());
                        } else {
                            throw new FetchException.BadUrl("path '" + path + "' is not a flake (because it's not a directory)");
                        }
                    }
                    if (!allowMissing && Fs.maybeLstat(path + "/flake.nix") == null) {
                        long device = Fs.lstat(path).dev();
                        boolean found = false;
                        while (!path.equals("/")) {
                            if (Fs.maybeLstat(path + "/flake.nix") != null) {
                                found = true;
                                break;
                            } else if (Fs.maybeLstat(path + "/.git") != null) {
                                throw new FetchException("path '" + path + "' is not part of a flake (neither it nor its parent directories contain a 'flake.nix' file)");
                            } else if (Fs.lstat(path).dev() != device) {
                                throw new FetchException("unable to find a flake before encountering filesystem boundary at '" + path + "'");
                            }
                            path = parent(path);
                        }
                        if (!found) throw new FetchException.BadUrl("could not find a flake.nix file");
                    }
                    if (!allowMissing && Fs.maybeLstat(path + "/flake.nix") == null) {
                        throw new FetchException.BadUrl("path '" + path + "' is not a flake (because it doesn't contain a 'flake.nix' file)");
                    }
                    String flakeRoot = path;
                    String subdir = "";
                    while (!flakeRoot.equals("/")) {
                        if (Fs.maybeLstat(flakeRoot + "/.git") != null) {
                            Url parsed = new Url("git+file", Url.Authority.empty(), Url.pathToUrlPath(flakeRoot), query, fragment);
                            if (!subdir.isEmpty()) {
                                if (parsed.query.containsKey("dir")) throw new FetchException("flake URL '" + url + "' has an inconsistent 'dir' parameter");
                                parsed.query.put("dir", subdir);
                            }
                            if (Fs.maybeLstat(flakeRoot + "/.git/shallow") != null) parsed.query.put("shallow", "1");
                            return fromParsedUrl(f, parsed, isFlake);
                        }
                        String name = flakeRoot.substring(flakeRoot.lastIndexOf('/') + 1);
                        subdir = name + (subdir.isEmpty() ? "" : "/" + subdir);
                        flakeRoot = parent(flakeRoot);
                    }
                } catch (IOException e) {
                    throw new FetchException(e.getMessage());
                }
            }
        } else if (!preserveRelativePaths && !path.startsWith("/")) {
            throw new FetchException.BadUrl("flake reference '" + url + "' is not an absolute path");
        }
        return fromParsedUrl(f, new Url("path", path.startsWith("/") ? Url.Authority.empty() : null, Url.pathToUrlPath(path), query, fragment), isFlake);
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }
}
