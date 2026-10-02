package nixtruffle.lsp;

import nixtruffle.parser.Expr;
import nixtruffle.parser.Expr.*;
import nixtruffle.parser.Expr.Binding.Assign;
import nixtruffle.parser.Expr.Binding.Inherit;
import nixtruffle.runtime.Bytes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * A document's outline (LSP DocumentSymbols): the bindings of its sets and {@code let}s, nested
 * as they are, through functions, {@code with}, and calls ({@code mkIf cond { ... }},
 * {@code mkMerge [ ... ]}). The tree has start offsets only: a binding ends where the next one
 * starts (or its set ends), without the white space before.
 */
final class DocumentSymbols {
    private final String text;
    private final IntFunction<Map<String, Object>> position;

    private DocumentSymbols(String text, IntFunction<Map<String, Object>> position) {
        this.text = text;
        this.position = position;
    }

    static List<Object> of(String text, Expr root, IntFunction<Map<String, Object>> position) {
        List<Object> out = new ArrayList<>();
        new DocumentSymbols(text, position).symbols(root, text.length(), out);
        return out;
    }

    /** The symbols in {@code e}, which ends at {@code end}. */
    private void symbols(Expr e, int end, List<Object> out) {
        switch (e) {
            case Lambda l -> symbols(l.body(), end, out);
            case Let l -> {
                bindings(l.bindings(), l.body().pos(), true, out);
                symbols(l.body(), end, out);
            }
            case Attrs a -> bindings(a.bindings(), end, false, out);
            case With w -> symbols(w.body(), end, out);
            case Assert a -> symbols(a.body(), end, out);
            case App app -> sequence(app.args(), end, out);
            case ListE l -> sequence(l.items(), end, out);
            default -> {}
        }
    }

    private void sequence(List<Expr> items, int end, List<Object> out) {
        for (int i = 0; i < items.size(); i++) symbols(items.get(i), i + 1 < items.size() ? items.get(i + 1).pos() : end, out);
    }

    private void bindings(List<Binding> binds, int end, boolean let, List<Object> out) {
        for (int i = 0; i < binds.size(); i++) {
            Binding b = binds.get(i);
            int stop = trim(b.pos(), i + 1 < binds.size() ? binds.get(i + 1).pos() : end);
            switch (b) {
                case Assign a -> {
                    StringBuilder name = new StringBuilder();
                    for (AttrKey k : a.path()) {
                        if (!name.isEmpty()) name.append('.');
                        name.append(k.name() != null ? k.name() : "${...}");
                    }
                    List<Object> children = new ArrayList<>();
                    symbols(a.value(), stop, children);
                    int keyEnd = keyEnd(a.pos());
                    out.add(symbol(name.toString(), kind(a.value(), let), let ? "let" : null, a.pos(), Math.max(stop, keyEnd), keyEnd, children));
                }
                case Inherit in -> {
                    for (int j = 0; j < in.names().size(); j++) {
                        int p = in.namePos().get(j);
                        int keyEnd = keyEnd(p);
                        out.add(symbol(in.names().get(j), let ? 13 : 8, "inherit", p, keyEnd, keyEnd, List.of()));
                    }
                }
            }
        }
    }

    /** The LSP SymbolKind of a binding's value. */
    private static long kind(Expr value, boolean let) {
        return switch (value) {
            case Lambda l -> 12;
            case Attrs a -> 3;
            case Let l -> kind(l.body(), let);
            case With w -> kind(w.body(), let);
            case ListE l -> 18;
            case Str s -> 15;
            case PathLit p -> 15;
            case Int i -> 16;
            case Flt f -> 16;
            case Var v when v.name().equals("true") || v.name().equals("false") -> 17;
            default -> let ? 13 : 8;
        };
    }

    /** {@code end} without the white space (and comments' line ends) before it. */
    private int trim(int start, int end) {
        int e = Math.min(end, text.length());
        while (e > start && Character.isWhitespace(text.charAt(e - 1))) e--;
        return e;
    }

    /** Where the first name of a binding starting at {@code pos} ends (a quoted one included). */
    private int keyEnd(int pos) {
        int i = pos;
        if (i < text.length() && text.charAt(i) == '"') {
            int q = text.indexOf('"', i + 1);
            return q < 0 ? i + 1 : q + 1;
        }
        while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || "_'-".indexOf(text.charAt(i)) >= 0)) i++;
        return Math.max(i, pos + 1);
    }

    private Map<String, Object> symbol(String name, long kind, String detail, int start, int end, int selectionEnd, List<Object> children) {
        Map<String, Object> s = LspServer.obj("name", Bytes.fromJava(name), "kind", kind,
                "range", LspServer.obj("start", position.apply(start), "end", position.apply(end)),
                "selectionRange", LspServer.obj("start", position.apply(start), "end", position.apply(selectionEnd)));
        if (detail != null) s.put("detail", detail);
        if (!children.isEmpty()) s.put("children", children);
        return s;
    }
}
