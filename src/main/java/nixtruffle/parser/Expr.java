package nixtruffle.parser;

import java.util.List;

/**
 * Syntax tree produced by {@link Parser}. {@code pos} is a character offset into the source.
 * Function bodies are translated when they first run, so their trees live as long as the code:
 * lists are copied to exact size (the parser's lists have room for ten elements, and most hold
 * one or two, which immutable lists keep without an array).
 */
public sealed interface Expr {
    int pos();

    record Int(long value, int pos) implements Expr {}
    record Flt(double value, int pos) implements Expr {}
    /** String with interpolation; each part is either a {@link String} or an {@link Expr}. */
    record Str(List<Object> parts, int pos) implements Expr {
        public Str { parts = List.copyOf(parts); }
        public boolean isLiteral() { return parts.stream().allMatch(p -> p instanceof String); }
        public String literal() { return String.join("", parts.stream().map(p -> (String) p).toList()); }
    }
    record PathLit(String text, int pos) implements Expr {}
    /** {@code ./patches/${x}.patch}: a path literal followed by strings and interpolations. */
    record PathInterp(String first, List<Object> rest, int pos) implements Expr {
        public PathInterp { rest = List.copyOf(rest); }
    }
    record SearchPath(String name, int pos) implements Expr {}
    record Var(String name, int pos) implements Expr {}
    /** {@code __curPos}: a parse-time special form, not a variable. */
    record CurPos(int pos) implements Expr {}
    record Select(Expr target, List<AttrKey> path, Expr fallback, int pos) implements Expr {
        public Select { path = List.copyOf(path); }
    }
    record HasAttr(Expr target, List<AttrKey> path, int pos) implements Expr {
        public HasAttr { path = List.copyOf(path); }
    }
    record App(Expr fn, List<Expr> args, int pos) implements Expr {
        public App { args = List.copyOf(args); }
    }
    /** {@code arg} is the plain argument name or the {@code @}-binding; {@code formals} is null for {@code x: body}. */
    record Lambda(String arg, Formals formals, Expr body, int pos) implements Expr {}
    record Let(List<Binding> bindings, Expr body, int pos) implements Expr {
        public Let { bindings = List.copyOf(bindings); }
    }
    record Attrs(boolean rec, List<Binding> bindings, int pos) implements Expr {
        public Attrs { bindings = List.copyOf(bindings); }
    }
    record ListE(List<Expr> items, int pos) implements Expr {
        public ListE { items = List.copyOf(items); }
    }
    record If(Expr cond, Expr then, Expr otherwise, int pos) implements Expr {}
    record With(Expr env, Expr body, int pos) implements Expr {}
    record Assert(Expr cond, Expr body, int pos) implements Expr {}
    record BinOp(String op, Expr left, Expr right, int pos) implements Expr {}
    record Not(Expr operand, int pos) implements Expr {}
    record Neg(Expr operand, int pos) implements Expr {}

    /** One attribute path component: either a static {@code name} or a dynamic {@code expr}. */
    record AttrKey(String name, Expr expr) {
        public static AttrKey of(String name) { return new AttrKey(name, null); }
    }

    record Formal(String name, Expr fallback, int pos) {}
    record Formals(List<Formal> formals, boolean ellipsis) {
        public Formals { formals = List.copyOf(formals); }
    }

    sealed interface Binding {
        int pos();
        record Assign(List<AttrKey> path, Expr value, int pos) implements Binding {
            public Assign { path = List.copyOf(path); }
        }
        /** {@code namePos} are the names' positions (their attributes' positions). */
        record Inherit(Expr from, List<String> names, List<Integer> namePos, int pos) implements Binding {
            public Inherit {
                names = List.copyOf(names);
                namePos = List.copyOf(namePos);
            }
        }
    }
}
