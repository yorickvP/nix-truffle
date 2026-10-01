package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/** Renders values like {@code nix-instantiate --eval --strict}. */
public final class Printer {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_'-]*");
    private static final Set<String> KEYWORDS = Set.of("if", "then", "else", "assert", "with", "let", "in", "rec", "inherit");

    private final boolean force;
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();

    private Printer(boolean force) { this.force = force; }

    /** With {@code force}, deeply forces the value (like {@code --strict}); otherwise shows «thunk» for unevaluated parts. */
    @TruffleBoundary
    public static String show(Object value, boolean force) {
        StringBuilder sb = new StringBuilder();
        new Printer(force).print(value, sb);
        return sb.toString();
    }

    /**
     * {@code builtins.deepSeq}: force everything reachable (CppNix's {@code forceValueDeep}; every
     * value is a level of {@code max-call-depth}, forced inside it).
     */
    @TruffleBoundary
    public static void deepForce(Object value) {
        deepForce(value, new IdentityHashMap<>());
    }

    private static void deepForce(Object value, IdentityHashMap<Object, Boolean> seen) {
        nixtruffle.NixContext ctx = CallDepth.enter(null);
        try {
            Object v = Thunk.force(value);
            if (v instanceof NixList l) {
                if (seen.put(l, true) != null) return;
                for (int i = 0; i < l.size(); i++) deepForce(l.items[i], seen);
            } else if (v instanceof NixAttrs a) {
                if (seen.put(a, true) != null) return;
                for (int i = 0; i < a.size(); i++) deepForce(a.values[i], seen);
            } else if (Foreign.isForeign(v)) {
                new Printer(true).print(v, new StringBuilder());
            }
        } finally {
            CallDepth.exit(ctx);
        }
    }

    private void print(Object v, StringBuilder sb) {
        if (v instanceof Thunk t) {
            if (force) {
                v = t.forceSlow();
            } else if (t.isDone()) {
                v = t.getValue();
            } else {
                sb.append(Bytes.fromJava("«thunk»"));
                return;
            }
        }
        switch (v) {
            case Long l -> sb.append(l);
            case Double d -> sb.append(Values.formatFloat(d));
            case Boolean b -> sb.append(b);
            case String s -> quote(s, sb);
            case NixString s -> quote(s.value, sb);
            case NixPath p -> sb.append(p.path);
            case NixNull n -> sb.append("null");
            case NixAttrs a -> printAttrs(a, sb);
            case NixList l -> {
                if (l.size() > 0 && seen.put(l, true) != null) {
                    sb.append(Bytes.fromJava("«repeated»"));
                    return;
                }
                sb.append("[ ");
                for (int i = 0; i < l.size(); i++) {
                    print(force ? l.forceAt(i) : l.items[i], sb);
                    sb.append(' ');
                }
                sb.append(']');
            }
            case NixLambda f -> sb.append("<LAMBDA>");
            case Builtin f -> sb.append("<PRIMOP>");
            case PartialApp f -> sb.append("<PRIMOP-APP>");
            default -> printForeign(v, sb);
        }
    }

    private void printAttrs(NixAttrs a, StringBuilder sb) {
        if (a.size() > 0 && seen.put(a, true) != null) {
            sb.append(Bytes.fromJava("«repeated»"));
            return;
        }
        sb.append("{ ");
        for (int i = 0; i < a.size(); i++) {
            String key = a.keys[i];
            if (IDENTIFIER.matcher(key).matches() && !KEYWORDS.contains(key)) {
                sb.append(key);
            } else {
                quote(key, sb);
            }
            sb.append(" = ");
            print(force ? a.forceAt(i) : a.values[i], sb);
            sb.append("; ");
        }
        sb.append('}');
    }

    private void printForeign(Object v, StringBuilder sb) {
        if (!Foreign.isForeign(v)) {
            sb.append(v);
            return;
        }
        Object[] items = Foreign.asArray(v);
        if (items != null) {
            print(new NixList(items), sb);
        } else if (Foreign.hasMembers(v) && !Foreign.isExecutable(v)) {
            printAttrs(Foreign.asAttrs(v), sb);
        } else {
            sb.append(Foreign.display(v));
        }
    }

    public static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '$' -> sb.append(i + 1 < s.length() && s.charAt(i + 1) == '{' ? "\\$" : "$");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }
}
