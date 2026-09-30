package nixtruffle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nix configuration, read like Nix does: {@code $NIX_CONF_DIR/nix.conf} (default {@code
 * /etc/nix}), the {@code nix/nix.conf} files in the XDG config directories, {@code $NIX_CONFIG},
 * and then the command line ({@code --option name value}, {@code --extra-experimental-features}).
 * {@code extra-X} appends to {@code X}. Only the settings nix-truffle uses are interpreted; values
 * are Java text.
 *
 * <p>nix-truffle's own settings: {@code polyglot} (default true) enables the polyglot builtins
 * ({@code polyglotEval}, ...) and importing non-Nix files by extension.
 */
public final class Settings {
    private final Map<String, String> values = new LinkedHashMap<>();
    /** {@code -I} entries, which come before the {@code nix-path} setting. */
    public final List<String> includePath = new ArrayList<>();

    public Settings() {
        values.put("experimental-features", "");
        values.put("flake-registry", "https://channels.nixos.org/flake-registry.json");
        values.put("tarball-ttl", "3600");
        values.put("allow-dirty", "true");
        values.put("warn-dirty", "true");
        values.put("use-registries", "true");
        values.put("accept-flake-config", "false");
        values.put("access-tokens", "");
        values.put("pure-eval", "false");
        values.put("polyglot", "true");
        values.put("trace-verbose", "false");
    }

    /** The settings from the configuration files and {@code $NIX_CONFIG}. */
    public static Settings load() {
        Settings s = new Settings();
        String confDir = System.getenv().getOrDefault("NIX_CONF_DIR", "/etc/nix");
        s.readFile(Path.of(confDir, "nix.conf"), false);
        String userConf = System.getenv("NIX_USER_CONF_FILES");
        if (userConf != null) {
            for (String f : userConf.split(":")) if (!f.isEmpty()) s.readFile(Path.of(f), false);
        } else {
            List<String> dirs = new ArrayList<>();
            String dataHome = System.getenv("XDG_CONFIG_HOME");
            dirs.add(dataHome != null ? dataHome : System.getProperty("user.home") + "/.config");
            String configDirs = System.getenv().getOrDefault("XDG_CONFIG_DIRS", "/etc/xdg");
            dirs.addAll(Arrays.asList(configDirs.split(":")));
            // Later files take precedence: read the least important first.
            for (int i = dirs.size() - 1; i >= 0; i--) {
                if (!dirs.get(i).isEmpty()) s.readFile(Path.of(dirs.get(i), "nix", "nix.conf"), false);
            }
        }
        String env = System.getenv("NIX_CONFIG");
        if (env != null) s.parse(env, null);
        return s;
    }

    private void readFile(Path file, boolean mustExist) {
        try {
            parse(Files.readString(file, StandardCharsets.UTF_8), file.getParent());
        } catch (IOException e) {
            if (mustExist) throw new IllegalArgumentException("cannot read '" + file + "': " + e.getMessage());
        }
    }

    /** {@code name = value} lines, {@code #} comments, {@code include}/{@code !include}. */
    public void parse(String text, Path dir) {
        for (String raw : text.split("\n")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.strip();
            if (line.isEmpty()) continue;
            String[] words = line.split("[ \t]+");
            if (words[0].equals("include") || words[0].equals("!include")) {
                if (words.length != 2) continue;
                Path p = dir == null ? Path.of(words[1]) : dir.resolve(words[1]);
                readFile(p, words[0].equals("include"));
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            set(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
    }

    /** Sets a value; {@code extra-X} appends to {@code X}. */
    public void set(String name, String value) {
        if (name.startsWith("extra-")) {
            String base = name.substring("extra-".length());
            String old = values.getOrDefault(base, "");
            values.put(base, old.isEmpty() ? value : old + " " + value);
        } else {
            values.put(name, value);
        }
    }

    public String get(String name) {
        return values.get(name);
    }

    public boolean getBool(String name) {
        String v = values.get(name);
        return v != null && (v.equals("true") || v.equals("1") || v.equals("yes"));
    }

    public long getLong(String name, long fallback) {
        try {
            return Long.parseLong(values.getOrDefault(name, ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Set<String> experimentalFeatures() {
        Set<String> out = new LinkedHashSet<>();
        for (String f : values.getOrDefault("experimental-features", "").split("\\s+")) if (!f.isEmpty()) out.add(f);
        return out;
    }

    /** The {@code system} setting, or the host's system type (like {@code x86_64-linux}). */
    public static String currentSystem(Settings settings) {
        String system = settings.get("system");
        if (system != null) return system;
        return System.getProperty("os.arch").replace("amd64", "x86_64").replace("arm64", "aarch64") + "-"
                + System.getProperty("os.name").toLowerCase().replace("mac os x", "darwin");
    }

    public boolean isEnabled(String feature) {
        return experimentalFeatures().contains(feature);
    }

    /** {@code access-tokens}: host (or host/path prefix) -> token. */
    public Map<String, String> accessTokens() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String t : values.getOrDefault("access-tokens", "").split("\\s+")) {
            int eq = t.indexOf('=');
            if (eq > 0) out.put(t.substring(0, eq), t.substring(eq + 1));
        }
        return out;
    }

    /**
     * The lookup path entries ({@code prefix=path} or {@code path}): {@code -I}, then {@code
     * $NIX_PATH} if set, else the {@code nix-path} setting, else the default channels.
     */
    public List<String> nixPath() {
        List<String> out = new ArrayList<>(includePath);
        String env = System.getenv("NIX_PATH");
        String setting = values.get("nix-path");
        if (env != null) {
            out.addAll(parseNixPath(env));
        } else if (setting != null) {
            for (String e : setting.split("\\s+")) if (!e.isEmpty()) out.add(e);
        } else {
            String home = System.getProperty("user.home");
            addIfExists(out, home + "/.nix-defexpr/channels", null);
            addIfExists(out, "/nix/var/nix/profiles/per-user/root/channels/nixpkgs", "nixpkgs");
            addIfExists(out, "/nix/var/nix/profiles/per-user/root/channels", null);
        }
        return out;
    }

    private static void addIfExists(List<String> out, String path, String prefix) {
        if (Files.exists(Path.of(path))) out.add(prefix == null ? path : prefix + "=" + path);
    }

    /** CppNix's {@code parseNixPath}: split at ':', except inside URLs and {@code flake:} refs. */
    public static List<String> parseNixPath(String s) {
        List<String> res = new ArrayList<>();
        int p = 0;
        int n = s.length();
        while (p != n) {
            int start = p;
            int start2 = p;
            while (p != n && s.charAt(p) != ':') {
                if (s.charAt(p) == '=') start2 = p + 1;
                p++;
            }
            if (p == n) {
                if (p != start) res.add(s.substring(start, p));
                break;
            }
            String prefix = s.substring(start2);
            if (isPseudoUrl(prefix) || prefix.startsWith("flake:")) {
                p++;
                while (p != n && s.charAt(p) != ':') p++;
            }
            res.add(s.substring(start, p));
            if (p == n) break;
            p++;
        }
        return res;
    }

    public static boolean isPseudoUrl(String s) {
        if (s.startsWith("channel:")) return true;
        int pos = s.indexOf("://");
        if (pos < 0) return false;
        String scheme = s.substring(0, pos);
        return switch (scheme) {
            case "http", "https", "file", "channel", "git", "s3", "ssh" -> true;
            default -> false;
        };
    }

    public static String resolvePseudoUrl(String url) {
        if (url.startsWith("channel:")) return "https://channels.nixos.org/" + url.substring(8) + "/nixexprs.tar.xz";
        return url;
    }
}
