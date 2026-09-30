package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A port of CppNix's value printer ({@code libexpr/print.cc}) with the options {@code nix eval}
 * uses: values are forced, derivations are shown as {@code «derivation /nix/store/…drv»},
 * repeated sets and lists as {@code «repeated»}, and errors inside the value as {@code «error: …»}.
 * Strings are byte strings and so is the result.
 */
public final class ValuePrinter {
    private static final Set<String> RESERVED = Set.of("if", "then", "else", "assert", "with", "let", "in", "rec", "inherit");
    /** All empty sets are the same one in CppNix, so a second one is «repeated». */
    private static final Object EMPTY_ATTRS = new Object();

    private final StringBuilder out = new StringBuilder();
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private final Set<String> context;
    /** {@code nix eval}'s options, or {@code errorPrintOptions}: not forced, and cut short. */
    private final boolean errorOptions;
    private int attrsPrinted, listItemsPrinted;

    private static final int ERROR_MAX = 10;
    private static final int ERROR_MAX_STRING = 1024;

    private ValuePrinter(Set<String> context, boolean errorOptions) {
        this.context = context;
        this.errorOptions = errorOptions;
    }

    /** {@code nix eval}'s output for {@code value}; the context of the strings in it goes to {@code context}. */
    @TruffleBoundary
    public static String print(Object value, Set<String> context) {
        ValuePrinter p = new ValuePrinter(context, false);
        p.print(value, 0);
        return p.out.toString();
    }

    /**
     * A value in an error message ({@code errorPrintOptions}): what isn't evaluated yet is
     * {@code «thunk»}, and it stops after 10 levels, attributes, list items or 1024 bytes.
     */
    @TruffleBoundary
    public static String printForError(Object value) {
        ValuePrinter p = new ValuePrinter(new java.util.TreeSet<>(), true);
        p.print(value, 0);
        return p.out.toString();
    }

    private void elided(int n, String single, String plural) {
        out.append(Bytes.fromJava("«")).append(n).append(' ').append(n == 1 ? single : plural).append(Bytes.fromJava(" elided»"));
    }

    private void print(Object raw, int depth) {
        int mark = out.length();
        try {
            if (errorOptions && raw instanceof Thunk t && !t.isDone()) {
                out.append(Bytes.fromJava("«thunk»"));
                return;
            }
            Object v = Thunk.force(raw);
            switch (v) {
                case NixAttrs a -> printAttrs(a, depth);
                case NixList l -> printList(l, depth);
                case NixLambda f -> printLambda(f);
                case Builtin b -> out.append(Bytes.fromJava("«primop ")).append(b.name).append(Bytes.fromJava("»"));
                case PartialApp p -> out.append(Bytes.fromJava("«partially applied primop ")).append(p.fn.name).append(Bytes.fromJava("»"));
                case Double d -> out.append(Values.formatFloat(d));
                case NixPath p -> out.append(p.path);
                default -> {
                    if (NixString.is(v)) {
                        String str = NixString.value(v);
                        if (errorOptions && str.length() > ERROR_MAX_STRING) {
                            Printer.quote(str.substring(0, ERROR_MAX_STRING), out);
                            out.setLength(out.length() - 1);
                            out.append("\" ");
                            elided(str.length() - ERROR_MAX_STRING, "byte", "bytes");
                        } else {
                            Printer.quote(str, out);
                        }
                        NixString.addContext(v, context);
                    } else {
                        out.append(Printer.show(v, false));
                    }
                }
            }
        } catch (NixException e) {
            out.setLength(mark);
            String msg = e.getMessage();
            int trace = msg.indexOf("\n       … ");
            out.append(Bytes.fromJava("«error: ")).append(trace < 0 ? msg : msg.substring(0, trace)).append(Bytes.fromJava("»"));
        }
    }

    private void printLambda(NixLambda f) {
        out.append(Bytes.fromJava("«lambda"));
        String name = f.info.name();
        if (name != null && !name.isEmpty() && !name.equals(NixLambda.ANONYMOUS)) out.append(' ').append(name);
        var at = f.target.getRootNode().getSourceSection();
        if (at != null && at.isAvailable()) {
            out.append(" @ ").append(at.getSource().getName()).append(':').append(at.getStartLine()).append(':').append(at.getStartColumn());
        }
        out.append(Bytes.fromJava("»"));
    }

    private void printAttrs(NixAttrs a, int depth) {
        if (seen.put(a.size() == 0 ? EMPTY_ATTRS : a, true) != null) {
            out.append(Bytes.fromJava("«repeated»"));
            return;
        }
        if (!errorOptions && Derivations.isDerivation(a)) {
            out.append(Bytes.fromJava("«derivation"));
            Object drvPath = a.getRaw("drvPath");
            if (drvPath != null) {
                String path = Values.coerce(drvPath, false, false, new java.util.TreeSet<>(), null);
                if (nixtruffle.store.StorePaths.parseStorePath(path) == null) {
                    throw NixException.error("path '" + path + "' is not in the Nix store", null);
                }
                out.append(' ').append(path);
            }
            out.append(Bytes.fromJava("»"));
            return;
        }
        if (errorOptions && depth >= ERROR_MAX) {
            out.append("{ ... }");
            return;
        }
        String[] keys = a.keys.clone();
        // With a limit on attributes, `type` and `_type` come first.
        if (errorOptions) {
            Arrays.sort(keys, (x, y) -> {
                boolean ix = x.equals("type") || x.equals("_type"), iy = y.equals("type") || y.equals("_type");
                return ix != iy ? (ix ? -1 : 1) : x.compareTo(y);
            });
        } else {
            Arrays.sort(keys);
        }
        out.append('{');
        int printed = 0;
        for (String key : keys) {
            out.append(' ');
            if (errorOptions && attrsPrinted >= ERROR_MAX) {
                elided(keys.length - printed, "attribute", "attributes");
                break;
            }
            printAttributeName(key);
            out.append(" = ");
            print(a.getRaw(key), depth + 1);
            out.append(';');
            attrsPrinted++;
            printed++;
        }
        out.append(" }");
    }

    private void printList(NixList l, int depth) {
        if (l.size() > 0 && seen.put(l, true) != null) {
            out.append(Bytes.fromJava("«repeated»"));
            return;
        }
        if (errorOptions && depth >= ERROR_MAX) {
            out.append("[ ... ]");
            return;
        }
        out.append('[');
        for (int i = 0; i < l.size(); i++) {
            out.append(' ');
            if (errorOptions && listItemsPrinted >= ERROR_MAX) {
                elided(l.size() - i, "item", "items");
                break;
            }
            print(l.items[i], depth + 1);
            listItemsPrinted++;
        }
        out.append(" ]");
    }

    /** {@code printAttributeName}: an identifier as it is, anything else as a string literal. */
    private void printAttributeName(String name) {
        if (isVarName(name)) out.append(name); else Printer.quote(name, out);
    }

    private static boolean isVarName(String s) {
        if (s.isEmpty() || RESERVED.contains(s)) return false;
        char c = s.charAt(0);
        if (c >= '0' && c <= '9' || c == '-' || c == '\'') return false;
        for (int i = 0; i < s.length(); i++) {
            char d = s.charAt(i);
            if (!(d >= 'a' && d <= 'z' || d >= 'A' && d <= 'Z' || d >= '0' && d <= '9' || d == '_' || d == '-' || d == '\'')) return false;
        }
        return true;
    }
}
