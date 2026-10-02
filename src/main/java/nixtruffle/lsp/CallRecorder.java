package nixtruffle.lsp;

import nixtruffle.runtime.Apply;
import nixtruffle.runtime.Builtin;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixLambda;
import nixtruffle.runtime.Thunk;

import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the arguments a file's function is called with (the language server's
 * __nixTruffle.callsTo): while one is active, importing that file gives a function that records
 * its argument and calls the file's. It is a set with {@code __functor} and
 * {@code __functionArgs} (the file's function's), so callPackage gives it the same arguments.
 */
public final class CallRecorder {
    public final String file;
    public final List<Object> calls = new CopyOnWriteArrayList<>();

    public CallRecorder(String file) {
        this.file = file;
    }

    /** What {@code import} gives for the file: its value, or, a function, one that records its calls. */
    public Object wrap(Object imported) {
        Object real = Thunk.force(imported);
        if (!(real instanceof NixLambda l)) return real;
        TreeMap<String, Object> args = new TreeMap<>();
        if (l.info.hasFormals()) {
            for (int i = 0; i < l.info.formals().length; i++) args.put(l.info.formals()[i], l.info.hasDefault()[i]);
        }
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("__functor", new Builtin("recordedCall", 2, a -> {
            calls.add(a[1]);
            return Thunk.force(Apply.apply(real, a[1], null));
        }));
        m.put("__functionArgs", NixAttrs.fromMap(args));
        return NixAttrs.fromMap(m);
    }
}
