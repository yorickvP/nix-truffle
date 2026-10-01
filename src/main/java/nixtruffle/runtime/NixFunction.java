package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/** Lambdas, builtins and partially applied builtins. Other languages call them with curried semantics. */
@ExportLibrary(InteropLibrary.class)
public abstract class NixFunction extends NixObject {
    @ExportMessage
    final boolean isExecutable() { return true; }

    @ExportMessage
    @TruffleBoundary
    public final Object execute(Object[] arguments) {
        Object f = this;
        for (Object arg : arguments) f = Apply.applyFromHost(f, Foreign.toNix(arg));
        return Foreign.out(Thunk.force(f));
    }
}
