package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * A non-ASCII Nix string on its way to another language: an interop string (decoded as UTF-8
 * for them), which turns back into exactly the same bytes when it comes back into Nix, even if
 * they aren't valid UTF-8.
 */
@ExportLibrary(InteropLibrary.class)
public final class ByteString extends NixObject {
    public final String value;

    ByteString(String value) {
        this.value = value;
    }

    @ExportMessage
    boolean isString() { return true; }

    @ExportMessage
    @TruffleBoundary
    String asString() { return Bytes.toJava(value); }

    @Override
    public String toString() { return value; }
}
