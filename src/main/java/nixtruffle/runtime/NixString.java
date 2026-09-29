package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * A string with a non-empty context: the store paths it refers to, which is how derivations learn
 * their inputs. Context-free strings are plain {@link String}s. Elements use Nix's encoding:
 * {@code /nix/store/..} (a path), {@code =/nix/store/...drv} (a derivation and its whole closure),
 * {@code !out!/nix/store/...drv} (an output of a derivation).
 */
@ExportLibrary(InteropLibrary.class)
public final class NixString extends NixObject {
    public final String value;
    public final String[] context;

    private NixString(String value, String[] context) {
        this.value = value;
        this.context = context;
    }

    @TruffleBoundary
    public static Object make(String value, Collection<String> context) {
        if (context == null || context.isEmpty()) return value;
        return new NixString(value, new TreeSet<>(context).toArray(new String[0]));
    }

    public static boolean is(Object v) {
        return v instanceof String || v instanceof NixString;
    }

    public static String value(Object v) {
        return v instanceof NixString s ? s.value : (String) v;
    }

    /** Adds {@code v}'s context (if any) to {@code out}. */
    @TruffleBoundary
    public static void addContext(Object v, Set<String> out) {
        if (out != null && v instanceof NixString s) out.addAll(java.util.Arrays.asList(s.context));
    }

    @ExportMessage
    boolean isString() { return true; }

    @ExportMessage
    String asString() { return value; }

    @Override
    public String toString() { return value; }
}
