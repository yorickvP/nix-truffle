package nixtruffle.fetch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * A port of CppNix's {@code ParsedURL} ({@code libutil/url.cc}): URLs are parsed with RFC 3986's
 * grammar (as boost.url, which Nix uses, implements it), path segments, query and fragment are
 * percent-decoded, and printing percent-encodes them again the way Nix does. All strings are
 * byte strings.
 */
public final class Url {
    public enum HostType { NAME, IPV4, IPV6, IPVFUTURE }

    public record Authority(HostType hostType, String host, String user, String password, Integer port) {
        public static Authority empty() {
            return new Authority(HostType.NAME, "", null, null, null);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (user != null) {
                sb.append(percentEncode(user, ""));
                if (password != null) sb.append(':').append(percentEncode(password, ""));
                sb.append('@');
            }
            switch (hostType) {
                case NAME -> sb.append(percentEncode(host, ""));
                case IPV4 -> sb.append(host);
                default -> sb.append('[').append(percentEncode(host, ":")).append(']');
            }
            if (port != null) sb.append(':').append(port);
            return sb.toString();
        }
    }

    public String scheme;
    public Authority authority;
    /** Decoded path segments; an absolute path starts with an empty one. */
    public List<String> path;
    public TreeMap<String, String> query;
    public String fragment;

    public Url(String scheme, Authority authority, List<String> path, TreeMap<String, String> query, String fragment) {
        this.scheme = scheme;
        this.authority = authority;
        this.path = new ArrayList<>(path);
        this.query = query == null ? new TreeMap<>() : new TreeMap<>(query);
        this.fragment = fragment == null ? "" : fragment;
    }

    public Url copy() {
        return new Url(scheme, authority, path, query, fragment);
    }

    private static FetchException.BadUrl bad(String message) {
        return new FetchException.BadUrl(message);
    }

    // ------------------------------------------------------ character sets

    static boolean isUnreserved(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~';
    }

    static boolean isSubDelim(char c) {
        return "!$&'()*+,;=".indexOf(c) >= 0;
    }

    static boolean isPchar(char c) {
        return isUnreserved(c) || isSubDelim(c) || c == ':' || c == '@';
    }

    private static boolean isHex(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    /** Checks that {@code s} consists of allowed characters and valid percent-encodings. */
    private static void validate(String s, java.util.function.Predicate<Character> allowed, String url) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length() || !isHex(s.charAt(i + 1)) || !isHex(s.charAt(i + 2))) {
                    throw bad("'" + url + "' is not a valid URL: bad percent-encoding");
                }
                i += 2;
            } else if (!allowed.test(c)) {
                throw bad("'" + url + "' is not a valid URL: invalid character");
            }
        }
    }

    // ------------------------------------------------------------ encoding

    /** boost::urls::encode: everything but unreserved characters and {@code keep} as %XX. */
    public static String percentEncode(String s, String keep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isUnreserved(c) || keep.indexOf(c) >= 0) {
                sb.append(c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4 & 0xf, 16)))
                  .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    public static String percentDecode(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length() || !isHex(s.charAt(i + 1)) || !isHex(s.charAt(i + 2))) {
                    throw bad("invalid URI parameter '" + s + "': bad percent-encoding");
                }
                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String encodeCharSet(String s, String chars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (chars.indexOf(c) >= 0) sb.append(percentEncode(String.valueOf(c), "")); else sb.append(c);
        }
        return sb.toString();
    }

    public static String encodeUrlPath(List<String> path) {
        List<String> out = new ArrayList<>();
        for (String p : path) out.add(percentEncode(p, ":@"));
        return String.join("/", out);
    }

    public static String encodeQuery(TreeMap<String, String> q) {
        StringBuilder sb = new StringBuilder();
        for (var e : q.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(percentEncode(e.getKey(), ":@/?")).append('=').append(percentEncode(e.getValue(), ":@/?"));
        }
        return sb.toString();
    }

    /** {@code decodeQuery}: {@code k=v} pairs; ones without '=' are ignored, the first of equal keys wins. */
    public static TreeMap<String, String> decodeQuery(String query, boolean lenient) {
        if (lenient) query = encodeCharSet(query, " \"");
        TreeMap<String, String> result = new TreeMap<>();
        if (query.isEmpty()) return result;
        for (String param : query.split("&", -1)) {
            validate(param, c -> isPchar(c) || c == '/' || c == '?', query);
            int eq = param.indexOf('=');
            if (eq < 0) continue;
            result.putIfAbsent(percentDecode(param.substring(0, eq)), percentDecode(param.substring(eq + 1)));
        }
        return result;
    }

    // ------------------------------------------------------------- parsing

    /** {@code parseURL}; {@code lenient} allows unescaped spaces and quotes in the query and fragment. */
    public static Url parse(String url, boolean lenient) {
        String s = lenient ? fixLenient(url) : url;
        // scheme ":"
        int colon = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':') {
                colon = i;
                break;
            }
            boolean ok = i == 0 ? (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z')
                    : (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '+' || c == '-' || c == '.');
            if (!ok) break;
        }
        if (colon <= 0) throw bad("'" + url + "' is not a valid URL: doesn't have a scheme");
        String scheme = s.substring(0, colon);
        String rest = s.substring(colon + 1);

        String fragment = null;
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
            validate(fragment, c -> isPchar(c) || c == '/' || c == '?', url);
        }
        String query = null;
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
            validate(query, c -> isPchar(c) || c == '/' || c == '?', url);
        }
        Authority authority = null;
        String encodedPath = rest;
        if (rest.startsWith("//")) {
            int end = rest.indexOf('/', 2);
            if (end < 0) end = rest.length();
            authority = parseAuthority(rest.substring(2, end), url);
            encodedPath = rest.substring(end);
        }
        validate(encodedPath, c -> isPchar(c) || c == '/', url);

        boolean transportIsFile = parseScheme(scheme)[1].equals("file");
        if (authority != null && !authority.host().isEmpty() && transportIsFile) {
            throw bad("file:// URL '" + url + "' has unexpected authority '" + authority + "'");
        }
        if (transportIsFile && encodedPath.isEmpty()) encodedPath = "/";
        List<String> path = new ArrayList<>();
        for (String seg : encodedPath.split("/", -1)) path.add(percentDecode(seg));
        return new Url(scheme, authority, path, query == null ? new TreeMap<>() : decodeQuery(query, lenient),
                fragment == null ? "" : percentDecode(fragment));
    }

    private static String fixLenient(String url) {
        StringBuilder fixed = new StringBuilder();
        String view = url;
        int q = view.indexOf('?');
        if (q >= 0) {
            fixed.append(view, 0, q + 1);
            view = view.substring(q + 1);
            int f = view.indexOf('#');
            String queryView = f < 0 ? view : view.substring(0, f);
            fixed.append(encodeCharSet(queryView, " \""));
            view = f < 0 ? "" : view.substring(f);
        }
        int h = view.indexOf('#');
        if (h >= 0) {
            fixed.append(view, 0, h + 1);
            fixed.append(encodeCharSet(view.substring(h + 1), " \"^"));
            return fixed.toString();
        }
        return fixed.append(view).toString();
    }

    private static Authority parseAuthority(String a, String url) {
        String user = null;
        String password = null;
        String hostPort = a;
        int at = a.indexOf('@');
        if (at >= 0) {
            String userinfo = a.substring(0, at);
            validate(userinfo, c -> isUnreserved(c) || isSubDelim(c) || c == ':', url);
            hostPort = a.substring(at + 1);
            int c = userinfo.indexOf(':');
            user = percentDecode(c < 0 ? userinfo : userinfo.substring(0, c));
            if (c >= 0) password = percentDecode(userinfo.substring(c + 1));
        }
        String host;
        String port = null;
        HostType type;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) throw bad("invalid URL authority: '" + a + "'");
            host = hostPort.substring(1, close);
            String after = hostPort.substring(close + 1);
            if (!after.isEmpty()) {
                if (after.charAt(0) != ':') throw bad("invalid URL authority: '" + a + "'");
                port = after.substring(1);
            }
            type = host.startsWith("v") || host.startsWith("V") ? HostType.IPVFUTURE : HostType.IPV6;
            if (type == HostType.IPV6 && !isIpv6(host)) throw bad("invalid URL authority: '" + a + "'");
        } else {
            int c = hostPort.lastIndexOf(':');
            if (c >= 0) {
                port = hostPort.substring(c + 1);
                hostPort = hostPort.substring(0, c);
            }
            validate(hostPort, ch -> isUnreserved(ch) || isSubDelim(ch), url);
            type = isIpv4(hostPort) ? HostType.IPV4 : HostType.NAME;
            host = percentDecode(hostPort);
        }
        Integer portNumber = null;
        if (port != null && !port.isEmpty()) {
            if (!port.chars().allMatch(ch -> ch >= '0' && ch <= '9')) throw bad("invalid URL authority: '" + a + "'");
            int n = port.length() > 5 ? 0 : Integer.parseInt(port);
            if (n <= 0 || n > 65535) throw bad("port '" + port + "' is invalid");
            portNumber = n;
        }
        return new Authority(type, host, user, password, portNumber);
    }

    private static boolean isIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(c -> c >= '0' && c <= '9')) return false;
            if (p.length() > 1 && p.charAt(0) == '0') return false;
            if (Integer.parseInt(p) > 255) return false;
        }
        return true;
    }

    private static boolean isIpv6(String s) {
        try {
            if (!s.matches("[0-9a-fA-F:.]+")) return false;
            java.net.InetAddress addr = java.net.InetAddress.getByName("[" + s + "]");
            return addr instanceof java.net.Inet6Address;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /** {@code parseUrlScheme}: {@code [application, transport]}; application may be null. */
    public static String[] parseScheme(String scheme) {
        int plus = scheme.indexOf('+');
        return plus < 0 ? new String[] {null, scheme} : new String[] {scheme.substring(0, plus), scheme.substring(plus + 1)};
    }

    // ------------------------------------------------------------ printing

    public String renderPath() {
        return encodeUrlPath(path);
    }

    @Override
    public String toString() {
        StringBuilder res = new StringBuilder(scheme).append(':');
        if (authority != null) res.append("//").append(authority);
        res.append(encodeUrlPath(path));
        if (!query.isEmpty()) res.append('?').append(encodeQuery(query));
        if (!fragment.isEmpty()) res.append('#').append(percentEncode(fragment, ""));
        return res.toString();
    }

    /** Path segments, optionally without empty ones. */
    public List<String> pathSegments(boolean skipEmpty) {
        if (!skipEmpty) return path;
        List<String> out = new ArrayList<>();
        for (String p : path) if (!p.isEmpty()) out.add(p);
        return out;
    }

    // ---------------------------------------------------- file system paths

    /** {@code pathToUrlPath}: "/a/b" is ["", "a", "b"]; a trailing slash adds an empty segment. */
    public static List<String> pathToUrlPath(String path) {
        List<String> out = new ArrayList<>();
        boolean absolute = path.startsWith("/");
        if (absolute) out.add("");
        for (String c : path.split("/")) if (!c.isEmpty()) out.add(c);
        // Iterating a std::filesystem::path with a trailing separator ends with an empty
        // element, and then the empty file name adds another: "a/" becomes "a//".
        if (!path.replaceAll("^/+", "").isEmpty() && path.endsWith("/")) out.add("");
        if (path.endsWith("/") || path.isEmpty()) out.add("");
        return out;
    }

    /** {@code urlPathToPath}. */
    public static String urlPathToPath(List<String> urlPath) {
        for (String c : urlPath) {
            if (c.contains("/")) throw bad("URL path component '" + c + "' contains '/', which is not allowed in file names");
            if (c.indexOf('\0') >= 0) throw bad("URL path component '" + c + "' contains NUL byte which is not allowed");
        }
        StringBuilder result = new StringBuilder();
        int i = 0;
        if (!urlPath.isEmpty() && urlPath.get(0).isEmpty()) {
            result.append('/');
            i = 1;
        }
        for (; i < urlPath.size(); i++) {
            String seg = urlPath.get(i);
            if (seg.isEmpty()) {
                if (!result.isEmpty() && result.charAt(result.length() - 1) != '/') result.append('/');
            } else {
                if (!result.isEmpty() && result.charAt(result.length() - 1) != '/') result.append('/');
                result.append(seg);
            }
        }
        return result.toString();
    }

    // ------------------------------------------------------------ git URLs

    private static final Set<String> SCHEMES_SUPPORTED_BY_GIT = Set.of("ssh", "http", "https", "file", "ftp", "ftps", "git",
            "git+ssh", "git+http", "git+https", "git+file", "git+ftp", "git+ftps", "git+git");

    /** {@code fixGitURL}: absolute paths become file URLs, SCP-style addresses ssh URLs, "git+" is dropped. */
    public static Url fixGitUrl(String url) {
        if (url.startsWith("/")) return new Url("file", Authority.empty(), pathToUrlPath(url), null, null);
        Url scp = tryParseScpStyle(url);
        if (scp != null) return scp;
        Url parsed = parse(url, false);
        String[] s = parseScheme(parsed.scheme);
        if ("git".equals(s[0])) parsed.scheme = s[1];
        return parsed;
    }

    private static Url tryParseScpStyle(String url) {
        if (url.contains("://")) return null;
        int firstColon = url.indexOf(':');
        if (firstColon < 0) return null;
        String schemeOrHost = url.substring(0, firstColon);
        if (schemeOrHost.contains("/")) return null;
        if (SCHEMES_SUPPORTED_BY_GIT.contains(schemeOrHost)) return null;
        int bracketStart = -1;
        int atBracket = url.indexOf("@[");
        if (atBracket >= 0) {
            bracketStart = atBracket + 1;
        } else if (url.startsWith("[")) {
            bracketStart = 0;
        }
        if (bracketStart >= 0) {
            int close = url.indexOf(']', bracketStart + 1);
            if (close >= 0) {
                if (close + 1 < url.length() && url.charAt(close + 1) == ':') {
                    schemeOrHost = url.substring(0, close + 1);
                } else {
                    return null;
                }
            }
        }
        String pathView = url.substring(schemeOrHost.length() + 1);
        String host = schemeOrHost;
        String user = null;
        int at = host.indexOf('@');
        if (at >= 0) {
            user = host.substring(0, at);
            host = host.substring(at + 1);
        }
        Authority authority;
        if (isIpv4(host)) {
            authority = new Authority(HostType.IPV4, host, user, null, null);
        } else if (host.startsWith("[") && host.endsWith("]")) {
            String inner = host.substring(1, host.length() - 1);
            if (!isIpv6(inner)) throw bad("Git SCP bracketed URL is not valid: '" + inner + "' is not a valid IPv6 address");
            authority = new Authority(HostType.IPV6, inner, user, null, null);
        } else {
            authority = new Authority(HostType.NAME, host, user, null, null);
        }
        if (pathView.isEmpty()) throw bad("SCP-style Git URL '" + url + "' has an empty path");
        List<String> path = new ArrayList<>(Arrays.asList(pathView.split("/", -1)));
        if (!path.isEmpty() && !path.get(0).isEmpty()) path.add(0, "");
        return new Url("ssh", authority, path, null, null);
    }
}
