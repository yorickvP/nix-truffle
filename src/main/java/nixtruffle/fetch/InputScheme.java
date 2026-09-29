package nixtruffle.fetch;

import java.util.Set;

/** A kind of input ({@code git}, {@code tarball}, ...), like libfetchers' {@code InputScheme}. */
interface InputScheme {
    String name();

    /** The input for a URL of this scheme, or null if the URL isn't one. */
    Input inputFromURL(Fetcher f, Url url, boolean requireTree);

    /** Validates attributes (their names are already checked against {@link #allowedAttrs}). */
    Input inputFromAttrs(Fetcher f, Attrs attrs);

    Set<String> allowedAttrs();

    default Url toURL(Input input) {
        throw new FetchException("don't know how to convert input '" + Attrs.Json.write(input.attrs) + "' to a URL");
    }

    boolean isLocked(Fetcher f, Input input);

    default boolean isDirect(Input input) {
        return true;
    }

    /** For inputs that are paths relative to their parent flake: the path. */
    default String isRelative(Input input) {
        return null;
    }

    /** A local directory the input comes from, if any (for flake sources that aren't copied). */
    default String getSourcePath(Input input) {
        return null;
    }

    /** Writes a file ({@code flake.lock}) into the input's source ({@code relPath} is relative to it). */
    default void putFile(Input input, String relPath, byte[] contents) {
        throw new FetchException("input '" + input + "' does not support modifying file '/" + relPath + "'");
    }

    /** Whether the scheme needs the {@code flakes} experimental feature. */
    default boolean needsFlakes() {
        return false;
    }

    default Input applyOverrides(Input input, String ref, String rev) {
        if (ref != null) throw new FetchException("don't know how to set branch/tag name of input '" + input + "' to '" + ref + "'");
        if (rev != null) throw new FetchException("don't know how to set revision of input '" + input + "' to '" + rev + "'");
        return input;
    }

    /**
     * Fetches the input into the store: its store path and NAR hash, and the input with the
     * attributes that lock it (rev, lastModified, ...).
     */
    Result fetch(Fetcher f, Input input);

    record Result(Fetcher.Tree tree, Input input) {}
}
