package nixtruffle.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/** A path value. Unlike real Nix we never copy paths into the store when they are coerced to strings. */
@ExportLibrary(InteropLibrary.class)
public final class NixPath extends NixObject {
    public final String path;

    public NixPath(String path) { this.path = path; }

    @ExportMessage
    boolean isString() { return true; }

    @ExportMessage
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    String asString() { return Bytes.toJava(path); }

    @Override
    public String toString() { return path; }

    /** Nix's canonPath: absolute, no '.', '..' or duplicate slashes; symlinks are not resolved. */
    public static String canonicalize(String path) {
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                parts.pollLast();
            } else {
                parts.addLast(seg);
            }
        }
        return "/" + String.join("/", parts);
    }
}
