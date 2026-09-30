package nixtruffle.parser;

import nixtruffle.parser.Expr.*;
import nixtruffle.parser.Expr.Binding.Assign;
import nixtruffle.parser.Expr.Binding.Inherit;
import nixtruffle.runtime.NixPath;
import nixtruffle.runtime.Printer;
import nixtruffle.runtime.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * CppNix's {@code Expr::show}: an expression as CppNix's parser desugars it ({@code a - b} is
 * {@code (__sub a b)}, {@code <p>} is {@code (__findFile __nixPath "p")}, paths are absolute), as
 * it appears in {@code assertion '…' failed}.
 */
public final class ExprShow {
    private static final Set<String> RESERVED = Set.of("if", "then", "else", "assert", "with", "let", "in", "rec", "inherit");

    private final String baseDir;
    private final StringBuilder out = new StringBuilder();

    private ExprShow(String baseDir) {
        this.baseDir = baseDir;
    }

    /** {@code e} as CppNix shows it; relative paths resolve against {@code baseDir}. */
    public static String show(Expr e, String baseDir) {
        ExprShow s = new ExprShow(baseDir);
        s.show(e);
        return s.out.toString();
    }

    private void show(Expr e) {
        switch (e) {
            case Int i -> out.append(i.value());
            case Flt f -> out.append(Values.formatFloat(f.value()));
            case Str s -> {
                if (s.isLiteral()) {
                    Printer.quote(s.literal(), out);
                } else {
                    concat(s.parts());
                }
            }
            case PathLit p -> out.append(path(p.text()));
            case PathInterp p -> {
                // The first segment is an absolute path, with its trailing slash.
                out.append('(').append(path(p.first()));
                if (p.first().length() > 1 && p.first().endsWith("/")) out.append('/');
                for (Object part : p.rest()) {
                    if (part instanceof String str && str.isEmpty()) continue;
                    out.append(" + ");
                    if (part instanceof String str) Printer.quote(str, out); else show((Expr) part);
                }
                out.append(')');
            }
            case SearchPath sp -> {
                out.append("(__findFile __nixPath ");
                Printer.quote(sp.name(), out);
                out.append(')');
            }
            case Var v -> identifier(v.name());
            case CurPos c -> out.append("__curPos");
            case Select s -> {
                out.append('(');
                show(s.target());
                out.append(").");
                attrPath(s.path());
                if (s.fallback() != null) {
                    out.append(" or (");
                    show(s.fallback());
                    out.append(')');
                }
            }
            case HasAttr h -> {
                out.append("((");
                show(h.target());
                out.append(") ? ");
                attrPath(h.path());
                out.append(')');
            }
            case App a -> {
                // Applications are flattened: `(f a) b` is one call with two arguments.
                List<Expr> args = new ArrayList<>();
                Expr fn = a;
                while (fn instanceof App app) {
                    args.addAll(0, app.args());
                    fn = app.fn();
                }
                out.append('(');
                show(fn);
                for (Expr arg : args) {
                    out.append(' ');
                    show(arg);
                }
                out.append(')');
            }
            case Lambda l -> lambda(l);
            case Let l -> {
                out.append("(let ");
                bindings(l.bindings());
                out.append("in ");
                show(l.body());
                out.append(')');
            }
            case Attrs a -> {
                if (a.rec()) out.append("rec ");
                out.append("{ ");
                bindings(a.bindings());
                out.append('}');
            }
            case ListE l -> {
                out.append("[ ");
                for (Expr item : l.items()) {
                    out.append('(');
                    show(item);
                    out.append(") ");
                }
                out.append(']');
            }
            case If i -> {
                out.append("(if ");
                show(i.cond());
                out.append(" then ");
                show(i.then());
                out.append(" else ");
                show(i.otherwise());
                out.append(')');
            }
            case With w -> {
                out.append("(with ");
                show(w.env());
                out.append("; ");
                show(w.body());
                out.append(')');
            }
            case Assert a -> {
                out.append("assert ");
                show(a.cond());
                out.append("; ");
                show(a.body());
            }
            case BinOp b -> binop(b);
            case Not n -> {
                out.append("(! ");
                show(n.operand());
                out.append(')');
            }
            case Neg n -> call("__sub", new Int(0, n.pos()), n.operand());
        }
    }

    private void binop(BinOp b) {
        switch (b.op()) {
            case "-" -> call("__sub", b.left(), b.right());
            case "*" -> call("__mul", b.left(), b.right());
            case "/" -> call("__div", b.left(), b.right());
            case "<" -> call("__lessThan", b.left(), b.right());
            case ">" -> call("__lessThan", b.right(), b.left());
            case "<=" -> {
                out.append("(! ");
                call("__lessThan", b.right(), b.left());
                out.append(')');
            }
            case ">=" -> {
                out.append("(! ");
                call("__lessThan", b.left(), b.right());
                out.append(')');
            }
            default -> {
                out.append('(');
                show(b.left());
                out.append(' ').append(b.op()).append(' ');
                show(b.right());
                out.append(')');
            }
        }
    }

    private void call(String fn, Expr a, Expr b) {
        out.append('(').append(fn).append(' ');
        show(a);
        out.append(' ');
        show(b);
        out.append(')');
    }

    /** {@code ExprConcatStrings}: the parts joined with {@code +}. */
    private void concat(List<Object> parts) {
        out.append('(');
        boolean first = true;
        for (Object p : parts) {
            if (p instanceof String s && s.isEmpty()) continue;
            if (!first) out.append(" + ");
            first = false;
            if (p instanceof String s) Printer.quote(s, out); else show((Expr) p);
        }
        out.append(')');
    }

    private String path(String text) {
        if (text.startsWith("~/")) return NixPath.canonicalize(nixtruffle.runtime.Bytes.fromJava(System.getProperty("user.home")) + text.substring(1));
        return NixPath.canonicalize(text.startsWith("/") ? text : baseDir + "/" + text);
    }

    private void lambda(Lambda l) {
        out.append('(');
        if (l.formals() != null) {
            out.append("{ ");
            List<Formal> formals = new ArrayList<>(l.formals().formals());
            formals.sort((x, y) -> x.name().compareTo(y.name()));
            boolean first = true;
            for (Formal f : formals) {
                if (!first) out.append(", ");
                first = false;
                identifier(f.name());
                if (f.fallback() != null) {
                    out.append(" ? ");
                    show(f.fallback());
                }
            }
            if (l.formals().ellipsis()) {
                if (!first) out.append(", ");
                out.append("...");
            }
            out.append(" }");
            if (l.arg() != null) out.append(" @ ");
        }
        if (l.arg() != null) identifier(l.arg());
        out.append(": ");
        show(l.body());
        out.append(')');
    }

    private void attrPath(List<AttrKey> path) {
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) out.append('.');
            AttrKey k = path.get(i);
            if (k.name() != null) {
                identifier(k.name());
            } else {
                out.append("\"${");
                show(k.expr());
                out.append("}\"");
            }
        }
    }

    /** {@code printIdentifier}. */
    private void identifier(String s) {
        if (s.isEmpty()) {
            out.append("\"\"");
            return;
        }
        if (RESERVED.contains(s)) {
            out.append('"').append(s).append('"');
            return;
        }
        char c = s.charAt(0);
        boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_';
        for (int i = 0; ok && i < s.length(); i++) {
            char d = s.charAt(i);
            ok = d >= 'a' && d <= 'z' || d >= 'A' && d <= 'Z' || d >= '0' && d <= '9' || d == '_' || d == '\'' || d == '-';
        }
        if (ok) out.append(s); else Printer.quote(s, out);
    }

    // ------------------------------------------------------------ bindings

    /** An attribute: its value, or nested bindings from {@code a.b = …}, or an inherit. */
    private static final class Attr {
        Expr value;
        List<Binding> nested;
        boolean inherited;
        int inheritFrom = -1;
    }

    /** {@code ExprAttrs::showBindings}: inherits, then the attributes by name, then dynamic ones. */
    private void bindings(List<Binding> bindings) {
        Map<String, Attr> attrs = new LinkedHashMap<>();
        List<Expr> inheritFrom = new ArrayList<>();
        List<Expr[]> dynamic = new ArrayList<>();
        for (Binding b : bindings) {
            switch (b) {
                case Inherit inh -> {
                    int from = -1;
                    if (inh.from() != null) {
                        from = inheritFrom.size();
                        inheritFrom.add(inh.from());
                    }
                    for (String name : inh.names()) {
                        Attr a = new Attr();
                        a.inherited = from < 0;
                        a.inheritFrom = from;
                        attrs.put(name, a);
                    }
                }
                case Assign a -> {
                    AttrKey first = a.path().get(0);
                    List<AttrKey> rest = a.path().subList(1, a.path().size());
                    Expr value = rest.isEmpty() ? a.value() : new Attrs(false, List.of(new Assign(rest, a.value(), a.pos())), a.pos());
                    if (first.name() == null) {
                        dynamic.add(new Expr[] {first.expr(), value});
                        continue;
                    }
                    Attr existing = attrs.get(first.name());
                    List<Binding> more = value instanceof Attrs av && !av.rec() ? av.bindings() : null;
                    if (existing != null && existing.nested != null && more != null) {
                        existing.nested.addAll(more);
                    } else {
                        Attr attr = new Attr();
                        if (more != null) {
                            attr.nested = new ArrayList<>(more);
                        } else {
                            attr.value = value;
                        }
                        attrs.put(first.name(), attr);
                    }
                }
            }
        }
        TreeMap<String, Attr> sorted = new TreeMap<>(attrs);
        List<String> inherits = new ArrayList<>();
        TreeMap<Integer, List<String>> byFrom = new TreeMap<>();
        for (Map.Entry<String, Attr> e : sorted.entrySet()) {
            if (e.getValue().inherited) inherits.add(e.getKey());
            else if (e.getValue().inheritFrom >= 0) byFrom.computeIfAbsent(e.getValue().inheritFrom, k -> new ArrayList<>()).add(e.getKey());
        }
        if (!inherits.isEmpty()) {
            out.append("inherit");
            for (String name : inherits) {
                out.append(' ');
                identifier(name);
            }
            out.append("; ");
        }
        for (Map.Entry<Integer, List<String>> e : byFrom.entrySet()) {
            out.append("inherit (");
            show(inheritFrom.get(e.getKey()));
            out.append(')');
            for (String name : e.getValue()) {
                out.append(' ');
                identifier(name);
            }
            out.append("; ");
        }
        for (Map.Entry<String, Attr> e : sorted.entrySet()) {
            Attr a = e.getValue();
            if (a.inherited || a.inheritFrom >= 0) continue;
            identifier(e.getKey());
            out.append(" = ");
            show(a.nested != null ? new Attrs(false, a.nested, 0) : a.value);
            out.append("; ");
        }
        for (Expr[] d : dynamic) {
            out.append("\"${");
            show(d[0]);
            out.append("}\" = ");
            show(d[1]);
            out.append("; ");
        }
    }
}
