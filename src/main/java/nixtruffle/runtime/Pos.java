package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.source.Source;

import java.util.TreeMap;

/**
 * A position in a Nix file, for {@code builtins.unsafeGetAttrPos} and {@code __curPos}. The
 * line and column are only computed when asked for; attribute set literals share one {@code Pos}
 * per attribute between all the sets they create.
 */
public final class Pos {
    private final Source source;
    private final String file;
    private final int offset;

    public Pos(Source source, String file, int offset) {
        this.source = source;
        this.file = file;
        this.offset = offset;
    }

    /** {@code { file, line, column }}, like CppNix's {@code mkPos} (columns count bytes). */
    @TruffleBoundary
    public NixAttrs toValue() {
        int p = Math.max(0, Math.min(offset, source.getLength() - 1));
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("file", file);
        m.put("line", (long) source.getLineNumber(p));
        m.put("column", (long) source.getColumnNumber(p));
        return NixAttrs.fromMap(m);
    }
}
