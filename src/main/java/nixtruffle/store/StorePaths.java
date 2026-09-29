package nixtruffle.store;

import java.util.Collection;

/** Store path construction, following libstore's {@code makeStorePath} and friends. */
public final class StorePaths {
    private StorePaths() {}

    public static final String STORE_DIR = "/nix/store";

    /** e.g. {@code makeStorePath("source", sha256:..., "foo.patch")}. */
    public static String makeStorePath(String type, String typedHash, String name) {
        String fingerprint = type + ":" + typedHash + ":" + STORE_DIR + ":" + name;
        byte[] h = Hash.compress(Hash.sha256(fingerprint).bytes(), 20);
        return STORE_DIR + "/" + Hash.base32(h) + "-" + name;
    }

    /** "text" or "source" plus the sorted references, as in libstore's makeType. */
    public static String makeType(String type, Collection<String> sortedRefs) {
        StringBuilder sb = new StringBuilder(type);
        for (String ref : sortedRefs) sb.append(':').append(ref);
        return sb.toString();
    }

    /** Path of a text file added with references (derivations, {@code builtins.toFile}). */
    public static String textPath(String name, String contents, Collection<String> sortedRefs) {
        return makeStorePath(makeType("text", sortedRefs), Hash.sha256(contents).typedHex(), name);
    }

    /** Fixed-output / content-addressed path of a file tree (recursive) or flat file. */
    public static String fixedOutputPath(boolean recursive, Hash hash, String name) {
        if (recursive && hash.algo().equals("sha256")) return makeStorePath("source", hash.typedHex(), name);
        String inner = "fixed:out:" + (recursive ? "r:" : "") + hash.typedHex() + ":";
        return makeStorePath("output:out", Hash.sha256(inner).typedHex(), name);
    }

    /** Output path of an input-addressed derivation. */
    public static String outputPath(String output, String hashHex, String drvName) {
        String name = output.equals("out") ? drvName : drvName + "-" + output;
        return makeStorePath("output:" + output, "sha256:" + hashHex, name);
    }

    /** {@code builtins.placeholder}. */
    public static String placeholder(String output) {
        return "/" + Hash.sha256("nix-output:" + output).base32();
    }

    /**
     * {@code parseStorePath}: the canonical form of {@code path} if it is a valid store path
     * (directly in the store, with a valid hash part and name), else null.
     */
    public static String parseStorePath(String path) {
        if (path.isEmpty() || path.charAt(0) != '/') return null;
        String p = nixtruffle.runtime.NixPath.canonicalize(path);
        int slash = p.lastIndexOf('/');
        if (!p.substring(0, slash).equals(STORE_DIR)) return null;
        String base = p.substring(slash + 1);
        if (base.length() < 33 || base.charAt(32) != '-') return null;
        for (int i = 0; i < 32; i++) {
            char c = base.charAt(i);
            if (c == 'e' || c == 'o' || c == 'u' || c == 't' || !(c >= '0' && c <= '9' || c >= 'a' && c <= 'z')) return null;
        }
        return checkName(base.substring(33)) == null ? p : null;
    }

    public static boolean isStorePath(String path) {
        return parseStorePath(path) != null && parseStorePath(path).equals(path);
    }

    /** The store path a path inside the store belongs to, or null. */
    public static String toStorePath(String path) {
        if (!path.startsWith(STORE_DIR + "/")) return null;
        int slash = path.indexOf('/', STORE_DIR.length() + 1);
        String sp = slash < 0 ? path : path.substring(0, slash);
        return parseStorePath(sp) != null ? sp : null;
    }

    public static final int MAX_NAME_LEN = 211;

    /** CppNix's {@code checkName}: an error message, or null if {@code name} is a valid store path name. */
    public static String checkName(String name) {
        if (name.isEmpty()) return "name must not be empty";
        if (name.length() > MAX_NAME_LEN) return "name '" + name + "' must be no longer than " + MAX_NAME_LEN + " characters";
        if (name.charAt(0) == '.') {
            if (name.length() == 1) return "name '" + name + "' is not valid";
            if (name.charAt(1) == '-') return "name '" + name + "' is not valid: first dash-separated component must not be '.'";
            if (name.charAt(1) == '.') {
                if (name.length() == 2) return "name '" + name + "' is not valid";
                if (name.charAt(2) == '-') return "name '" + name + "' is not valid: first dash-separated component must not be '..'";
            }
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c == '+' || c == '-' || c == '.' || c == '_' || c == '?' || c == '=';
            if (!ok) return "name '" + name + "' contains illegal character '" + c + "'";
        }
        return null;
    }
}
