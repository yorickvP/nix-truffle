package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/** A list; elements may be thunks. Interop reads force elements lazily. */
@ExportLibrary(InteropLibrary.class)
public final class NixList extends NixObject {
    public static final NixList EMPTY = new NixList(new Object[0]);

    public final Object[] items;

    public NixList(Object[] items) { this.items = items; }

    public int size() { return items.length; }

    /** Forces element {@code i} and writes the answer back so later reads skip the thunk. */
    public Object forceAt(int i) {
        Object v = items[i];
        if (v instanceof Thunk t) {
            v = t.forceSlow();
            items[i] = v;
        }
        return v;
    }

    @TruffleBoundary
    public static NixList concat(Object[] a, Object[] b) {
        Object[] out = new Object[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return new NixList(out);
    }

    @ExportMessage
    boolean hasArrayElements() { return true; }

    @ExportMessage
    long getArraySize() { return items.length; }

    @ExportMessage
    boolean isArrayElementReadable(long index) { return index >= 0 && index < items.length; }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (index < 0 || index >= items.length) throw InvalidArrayIndexException.create(index);
        return Foreign.out(forceAt((int) index));
    }
}
