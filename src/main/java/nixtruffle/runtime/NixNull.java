package nixtruffle.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
public final class NixNull extends NixObject {
    public static final NixNull INSTANCE = new NixNull();

    private NixNull() {}

    @ExportMessage
    boolean isNull() { return true; }

    @Override
    public String toString() { return "null"; }
}
