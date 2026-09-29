package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import nixtruffle.NixLanguage;

/** Base class of Nix heap values that are visible to other languages through interop. */
@ExportLibrary(InteropLibrary.class)
public abstract class NixObject implements TruffleObject {
    @ExportMessage
    final boolean hasLanguage() { return true; }

    @ExportMessage
    final Class<? extends TruffleLanguage<?>> getLanguage() { return NixLanguage.class; }

    @ExportMessage
    @TruffleBoundary
    final Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return Printer.show(this, allowSideEffects);
    }
}
