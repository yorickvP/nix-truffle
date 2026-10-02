package nixtruffle.lsp;

import nixtruffle.parser.Expr;
import nixtruffle.parser.Expr.*;
import nixtruffle.parser.Expr.Binding.Assign;

import java.util.ArrayList;
import java.util.List;

/**
 * What a module defines, from its syntax tree: each binding's option path (through nested sets,
 * {@code config = ...}, and calls of mkIf, mkMerge, mkDefault, ...), where its names are, and
 * where its value starts.
 */
final class ModuleDefinitions {
    private ModuleDefinitions() {}

    /** A definition: its option path, its names' extent, and its value's start. */
    record Definition(List<String> path, int keyStart, int keyEnd, int valuePos) {}

    /** A module's definitions: of a function of a set's body, or of a set (hardware-configuration.nix). */
    static List<Definition> of(String text, Expr root) {
        List<Definition> out = new ArrayList<>();
        if (root instanceof Lambda l && l.formals() != null) body(text, l.body(), List.of(), true, out);
        else if (root instanceof Attrs) body(text, root, List.of(), true, out);
        return out;
    }

    /** The definitions in {@code e}, the value of {@code path} ({@code top}: the module's own set). */
    private static void body(String text, Expr e, List<String> path, boolean top, List<Definition> out) {
        switch (e) {
            case Let let -> body(text, let.body(), path, top, out);
            case With w -> body(text, w.body(), path, top, out);
            case Assert a -> body(text, a.body(), path, top, out);
            case App app when isModuleFunction(app.fn()) -> {
                for (Expr x : app.args()) {
                    // mkMerge [ { ... } { ... } ]; other lists are values
                    if (x instanceof ListE l) for (Expr item : l.items()) body(text, item, path, top, out);
                    else body(text, x, path, top, out);
                }
            }
            case Attrs a when !a.rec() -> {
                for (Binding b : a.bindings()) {
                    if (!(b instanceof Assign as)) continue;
                    List<String> p = new ArrayList<>(path);
                    boolean dynamic = false;
                    for (AttrKey k : as.path()) {
                        if (k.name() == null) dynamic = true;
                        else p.add(k.name());
                    }
                    if (dynamic) continue;
                    if (top && (p.getFirst().equals("options") || p.getFirst().equals("imports") || p.getFirst().equals("disabledModules")
                            || p.getFirst().equals("_file") || p.getFirst().equals("key"))) continue;
                    if (top && p.getFirst().equals("config")) {
                        p.removeFirst();
                        if (p.isEmpty()) {
                            body(text, as.value(), p, false, out);
                            continue;
                        }
                    }
                    out.add(new Definition(List.copyOf(p), as.pos(), keyEnd(text, as), as.value().pos()));
                    body(text, as.value(), p, false, out);
                }
            }
            default -> {}
        }
    }

    /** {@code mkIf cond { ... }}, {@code mkMerge [ ... ]}, {@code lib.mkDefault { ... }}, ... */
    private static boolean isModuleFunction(Expr fn) {
        String name = fn instanceof Var v ? v.name() : fn instanceof Select s ? s.path().getLast().name() : null;
        return name != null && List.of("mkIf", "mkMerge", "mkDefault", "mkForce", "mkOverride", "mkBefore", "mkAfter", "mkOrder").contains(name);
    }

    /** Where a binding's names end: before the `=` that starts its value. */
    private static int keyEnd(String text, Assign a) {
        int e = a.value().pos();
        int eq = text.lastIndexOf('=', Math.max(0, e - 1));
        int end = eq < a.pos() ? e : eq;
        while (end > a.pos() && Character.isWhitespace(text.charAt(end - 1))) end--;
        return end;
    }
}
