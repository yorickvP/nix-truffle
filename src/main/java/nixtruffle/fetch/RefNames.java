package nixtruffle.fetch;

/**
 * {@code isLegalRefName}: whether a string is a valid git branch, tag or reference name, by
 * libgit2's rules ({@code git check-ref-format}).
 */
final class RefNames {
    private RefNames() {}

    static boolean isLegal(String name) {
        if (name.equals("@") || name.indexOf('\177') >= 0) return false;
        return isValidReference(name, true)
                || !name.startsWith("-") && !name.equals("HEAD") && isValidReference("refs/heads/" + name, false)
                || !name.startsWith("-") && isValidReference("refs/tags/" + name, false);
    }

    /** {@code git_reference__normalize_name}'s checks. */
    static boolean isValidReference(String name, boolean allowOneLevel) {
        if (name.isEmpty() || name.startsWith("/") || name.endsWith("/") || name.endsWith(".")) return false;
        if (name.contains("@{") || name.contains("..") || name.contains("//")) return false;
        String[] parts = name.split("/", -1);
        for (String c : parts) {
            if (c.isEmpty() || c.startsWith(".") || c.endsWith(".lock")) return false;
            for (int i = 0; i < c.length(); i++) {
                char ch = c.charAt(i);
                if (ch < 0x20 || ch == 0x7f || " ~^:?*[\\".indexOf(ch) >= 0) return false;
            }
        }
        if (parts.length == 1) {
            // One-level names must look like HEAD, FETCH_HEAD, ...
            if (!allowOneLevel) return false;
            for (int i = 0; i < name.length(); i++) {
                char ch = name.charAt(i);
                if (!(ch >= 'A' && ch <= 'Z' || ch == '_')) return false;
            }
        }
        return true;
    }
}
