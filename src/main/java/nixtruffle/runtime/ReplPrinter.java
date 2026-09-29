package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.IdentityHashMap;
import java.util.regex.Pattern;

/**
 * Pretty-printer for the REPL, modelled on {@code nix repl}: forces values up to {@code maxDepth},
 * prints deeper sets and lists as <code>{ ... }</code> / {@code [ ... ]}, shows derivations as
 * {@code «derivation /nix/store/...drv»}, and renders errors below the top level inline.
 */
public final class ReplPrinter {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_'-]*");

    private final int maxDepth;
    private final StringBuilder sb = new StringBuilder();
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();

    private ReplPrinter(int maxDepth) { this.maxDepth = maxDepth; }

    @TruffleBoundary
    public static String show(Object value, int maxDepth) {
        ReplPrinter p = new ReplPrinter(maxDepth);
        p.print(Thunk.force(value), 0, 0);
        return p.sb.toString();
    }

    private void printChild(Object raw, int depth, int indent) {
        Object v;
        try {
            v = Thunk.force(raw);
        } catch (NixException e) {
            sb.append("«error: ").append(e.getMessage().lines().findFirst().orElse("")).append('»');
            return;
        }
        print(v, depth, indent);
    }

    private void print(Object v, int depth, int indent) {
        switch (v) {
            case NixAttrs a -> printAttrs(a, depth, indent);
            case NixList l -> printList(l, depth, indent);
            case NixLambda f -> {
                var at = f.target.getRootNode().getSourceSection();
                sb.append("«lambda");
                if (at != null && at.isAvailable()) {
                    sb.append(" @ ").append(at.getSource().getName()).append(':').append(at.getStartLine()).append(':').append(at.getStartColumn());
                }
                sb.append('»');
            }
            case Builtin b -> sb.append("«primop ").append(b.name).append('»');
            case PartialApp p -> sb.append("«partially applied primop ").append(p.fn.name).append('»');
            default -> sb.append(Printer.show(v, false));
        }
    }

    private static boolean isNested(Object raw) {
        Object v = raw instanceof Thunk t && t.isDone() ? t.getValue() : raw;
        return v instanceof NixAttrs a && a.size() > 0 || v instanceof NixList l && l.size() > 0 || v instanceof Thunk;
    }

    private void newline(int indent) {
        sb.append('\n').append(" ".repeat(indent));
    }

    private void printAttrs(NixAttrs a, int depth, int indent) {
        if (Derivations.isDerivation(a)) {
            sb.append("«derivation ");
            try {
                sb.append(Values.coerceToString(a.get("drvPath"), false, null));
            } catch (NixException e) {
                sb.append("???");
            }
            sb.append('»');
            return;
        }
        if (a.size() == 0) {
            sb.append("{ }");
            return;
        }
        if (depth >= maxDepth) {
            sb.append("{ ... }");
            return;
        }
        if (seen.put(a, true) != null) {
            sb.append("«repeated»");
            return;
        }
        boolean pretty = a.size() > 1 || isNested(a.values[0]);
        sb.append('{');
        for (int i = 0; i < a.size(); i++) {
            if (pretty) newline(indent + 2); else sb.append(' ');
            String key = a.keys[i];
            if (IDENTIFIER.matcher(key).matches()) sb.append(key); else Printer.quote(key, sb);
            sb.append(" = ");
            printChild(a.values[i], depth + 1, indent + 2);
            sb.append(';');
        }
        if (pretty) newline(indent); else sb.append(' ');
        sb.append('}');
    }

    private void printList(NixList l, int depth, int indent) {
        if (l.size() == 0) {
            sb.append("[ ]");
            return;
        }
        if (depth >= maxDepth) {
            sb.append("[ ... ]");
            return;
        }
        if (seen.put(l, true) != null) {
            sb.append("«repeated»");
            return;
        }
        boolean pretty = l.size() > 1 || isNested(l.items[0]);
        sb.append('[');
        for (int i = 0; i < l.size(); i++) {
            if (pretty) newline(indent + 2); else sb.append(' ');
            printChild(l.items[i], depth + 1, indent + 2);
        }
        if (pretty) newline(indent); else sb.append(' ');
        sb.append(']');
    }
}
