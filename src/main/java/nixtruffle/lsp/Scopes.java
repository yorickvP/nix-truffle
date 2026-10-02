package nixtruffle.lsp;

import nixtruffle.parser.Expr;
import nixtruffle.parser.Expr.*;
import nixtruffle.parser.Expr.Binding.Assign;
import nixtruffle.parser.Expr.Binding.Inherit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nix's static scoping over a syntax tree, as CppNix binds variables: a name is the innermost
 * {@code let}, recursive set or function argument that defines it, else a global
 * ({@code builtins}, {@code map}, ...), and only then the innermost {@code with}'s.
 *
 * <p>{@link #analyze} resolves every variable; {@link #at} gives what is in scope at an offset.
 * The tree has start offsets only, so "the node at an offset" is, among a node's children in
 * source order, the last one that starts before it.
 */
public final class Scopes {
    /**
     * Where a name is defined: its offset, what defines it ("let", "argument", "rec"), and whether
     * renaming it changes nothing else (not a set's attribute, nor a function's attribute
     * argument, nor an inherited name).
     */
    public record Def(String name, int pos, String kind, boolean renamable) {}

    public enum Kind { LOCAL, GLOBAL, WITH, UNDEFINED }

    /** A variable, and what it resolves to ({@code def} for {@link Kind#LOCAL}); {@code inherited}: in {@code inherit x;}. */
    public record Use(String name, int pos, Kind kind, Def def, boolean inherited) {}

    /** A path literal (for "go to definition": the file). */
    public record PathRef(String text, int pos) {}

    /** The names in scope at an offset, innermost first, and whether a {@code with} is around. */
    public record Scope(List<Def> names, boolean with) {}

    private final String src;
    private final Set<String> globals;
    private boolean inheriting;
    public final List<Use> uses = new ArrayList<>();
    public final List<Def> defs = new ArrayList<>();
    public final List<PathRef> paths = new ArrayList<>();

    private Scopes(String src, Set<String> globals) {
        this.src = src;
        this.globals = globals;
    }

    /** An environment: a frame of names, or (when {@code names} is null) a {@code with}. */
    private record Env(Map<String, Def> names, Env up) {
        Def find(String name) {
            for (Env e = this; e != null; e = e.up) {
                if (e.names != null) {
                    Def d = e.names.get(name);
                    if (d != null) return d;
                }
            }
            return null;
        }

        boolean inWith() {
            for (Env e = this; e != null; e = e.up) if (e.names == null) return true;
            return false;
        }
    }

    public static Scopes analyze(String src, Expr root, Set<String> globals) {
        Scopes s = new Scopes(src, globals);
        s.walk(root, null);
        return s;
    }

    private void walk(Expr e, Env env) {
        switch (e) {
            case Var v -> {
                Def d = env == null ? null : env.find(v.name());
                Kind kind = d != null ? Kind.LOCAL
                        : globals.contains(v.name()) ? Kind.GLOBAL
                        : env != null && env.inWith() ? Kind.WITH
                        : Kind.UNDEFINED;
                uses.add(new Use(v.name(), v.pos(), kind, d, inheriting));
            }
            case PathLit p -> paths.add(new PathRef(p.text(), p.pos()));
            case PathInterp p -> {
                for (Object part : p.rest()) if (part instanceof Expr x) walk(x, env);
            }
            case Str str -> {
                for (Object part : str.parts()) if (part instanceof Expr x) walk(x, env);
            }
            case Select sel -> {
                walk(sel.target(), env);
                keys(sel.path(), env);
                if (sel.fallback() != null) walk(sel.fallback(), env);
            }
            case HasAttr h -> {
                walk(h.target(), env);
                keys(h.path(), env);
            }
            case App app -> {
                walk(app.fn(), env);
                for (Expr a : app.args()) walk(a, env);
            }
            case Lambda l -> {
                Env inner = new Env(lambdaNames(l), env);
                if (l.formals() != null) {
                    for (Formal f : l.formals().formals()) if (f.fallback() != null) walk(f.fallback(), inner);
                }
                walk(l.body(), inner);
            }
            case Let let -> {
                Env inner = new Env(bindingNames(let.bindings(), "let"), env);
                bindings(let.bindings(), env, inner);
                walk(let.body(), inner);
            }
            case Attrs a -> {
                if (a.rec()) {
                    Env inner = new Env(bindingNames(a.bindings(), "rec"), env);
                    bindings(a.bindings(), env, inner);
                } else {
                    bindings(a.bindings(), env, env);
                }
            }
            case ListE l -> {
                for (Expr x : l.items()) walk(x, env);
            }
            case If i -> {
                walk(i.cond(), env);
                walk(i.then(), env);
                walk(i.otherwise(), env);
            }
            case With w -> {
                walk(w.env(), env);
                walk(w.body(), new Env(null, env));
            }
            case Assert a -> {
                walk(a.cond(), env);
                walk(a.body(), env);
            }
            case BinOp b -> {
                walk(b.left(), env);
                walk(b.right(), env);
            }
            case Not n -> walk(n.operand(), env);
            case Neg n -> walk(n.operand(), env);
            case Int i -> {}
            case Flt f -> {}
            case SearchPath sp -> {}
            case CurPos c -> {}
        }
    }

    private void keys(List<AttrKey> path, Env env) {
        for (AttrKey k : path) if (k.expr() != null) walk(k.expr(), env);
    }

    /**
     * A set's or a {@code let}'s bindings: values in {@code inner} (the outer scope for a set that
     * isn't recursive); {@code inherit x} is the outer scope's {@code x}.
     */
    private void bindings(List<Binding> binds, Env outer, Env inner) {
        for (Binding b : binds) {
            switch (b) {
                case Assign a -> {
                    for (AttrKey k : a.path()) if (k.expr() != null) walk(k.expr(), outer);
                    walk(a.value(), inner);
                }
                case Inherit in -> {
                    if (in.from() != null) {
                        walk(in.from(), inner);
                    } else {
                        inheriting = true;
                        for (int i = 0; i < in.names().size(); i++) walk(new Var(in.names().get(i), in.namePos().get(i)), outer);
                        inheriting = false;
                    }
                }
            }
        }
    }

    private Map<String, Def> bindingNames(List<Binding> binds, String kind) {
        Map<String, Def> names = new LinkedHashMap<>();
        for (Binding b : binds) {
            switch (b) {
                case Assign a -> {
                    String name = a.path().getFirst().name();
                    if (name != null) names.putIfAbsent(name, def(name, a.pos(), kind, kind.equals("let")));
                }
                case Inherit in -> {
                    for (int i = 0; i < in.names().size(); i++) names.putIfAbsent(in.names().get(i), def(in.names().get(i), in.namePos().get(i), kind, false));
                }
            }
        }
        return names;
    }

    private Map<String, Def> lambdaNames(Lambda l) {
        Map<String, Def> names = new LinkedHashMap<>();
        if (l.formals() != null) {
            for (Formal f : l.formals().formals()) names.put(f.name(), def(f.name(), f.pos(), "argument", false));
        }
        if (l.arg() != null) {
            // `x: ...` and `x @ { ... }: ...` start with the name; `{ ... } @ x: ...` ends with it.
            int pos = src.startsWith(l.arg(), l.pos()) ? l.pos() : src.lastIndexOf(l.arg(), l.body().pos());
            names.put(l.arg(), def(l.arg(), pos, "argument", true));
        }
        return names;
    }

    private Def def(String name, int pos, String kind, boolean renamable) {
        Def d = new Def(name, pos, kind, renamable);
        defs.add(d);
        return d;
    }

    // ------------------------------------------------------------ at an offset

    /** The names in scope at {@code offset}. */
    public static Scope at(String src, Expr root, int offset) {
        Scopes s = new Scopes(src, Set.of());
        List<Def> names = new ArrayList<>();
        boolean[] with = {false};
        s.descend(root, offset, names, with);
        return new Scope(names, with[0]);
    }

    /** Collects the scopes on the way from {@code e} to the node at {@code offset}. */
    private void descend(Expr e, int offset, List<Def> names, boolean[] with) {
        switch (e) {
            case Lambda l -> {
                names.addAll(0, lambdaNames(l).values());
                descendInto(children(e), offset, names, with);
            }
            case Let let -> {
                names.addAll(0, bindingNames(let.bindings(), "let").values());
                descendInto(children(e), offset, names, with);
            }
            case Attrs a -> {
                // In a recursive set's values (not its keys), its names.
                if (a.rec()) names.addAll(0, bindingNames(a.bindings(), "rec").values());
                descendInto(children(e), offset, names, with);
            }
            case With w -> {
                if (offset >= w.body().pos()) {
                    with[0] = true;
                    descend(w.body(), offset, names, with);
                } else {
                    descend(w.env(), offset, names, with);
                }
            }
            default -> descendInto(children(e), offset, names, with);
        }
    }

    private void descendInto(List<Expr> children, int offset, List<Def> names, boolean[] with) {
        Expr in = null;
        for (Expr c : children) if (c.pos() <= offset) in = c;
        if (in != null) descend(in, offset, names, with);
    }

    /** A node's sub-expressions, in source order. */
    static List<Expr> children(Expr e) {
        List<Expr> out = new ArrayList<>();
        switch (e) {
            case PathInterp p -> {
                for (Object part : p.rest()) if (part instanceof Expr x) out.add(x);
            }
            case Str str -> {
                for (Object part : str.parts()) if (part instanceof Expr x) out.add(x);
            }
            case Select sel -> {
                out.add(sel.target());
                for (AttrKey k : sel.path()) if (k.expr() != null) out.add(k.expr());
                if (sel.fallback() != null) out.add(sel.fallback());
            }
            case HasAttr h -> {
                out.add(h.target());
                for (AttrKey k : h.path()) if (k.expr() != null) out.add(k.expr());
            }
            case App app -> {
                out.add(app.fn());
                out.addAll(app.args());
            }
            case Lambda l -> {
                if (l.formals() != null) for (Formal f : l.formals().formals()) if (f.fallback() != null) out.add(f.fallback());
                out.add(l.body());
            }
            case Let let -> {
                bindingChildren(let.bindings(), out);
                out.add(let.body());
            }
            case Attrs a -> bindingChildren(a.bindings(), out);
            case ListE l -> out.addAll(l.items());
            case If i -> out.addAll(List.of(i.cond(), i.then(), i.otherwise()));
            case With w -> out.addAll(List.of(w.env(), w.body()));
            case Assert a -> out.addAll(List.of(a.cond(), a.body()));
            case BinOp b -> out.addAll(List.of(b.left(), b.right()));
            case Not n -> out.add(n.operand());
            case Neg n -> out.add(n.operand());
            default -> {}
        }
        return out;
    }

    private static void bindingChildren(List<Binding> binds, List<Expr> out) {
        for (Binding b : binds) {
            switch (b) {
                case Assign a -> {
                    for (AttrKey k : a.path()) if (k.expr() != null) out.add(k.expr());
                    out.add(a.value());
                }
                case Inherit in -> {
                    if (in.from() != null) out.add(in.from());
                }
            }
        }
    }
}
