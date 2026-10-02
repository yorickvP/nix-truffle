package nixtruffle.lsp;

import com.oracle.truffle.api.source.Source;
import nixtruffle.NixContext;
import nixtruffle.parser.Expr;
import nixtruffle.parser.Expr.*;
import nixtruffle.parser.Expr.Binding.Assign;
import nixtruffle.parser.Parser;
import nixtruffle.runtime.Apply;
import nixtruffle.runtime.Thunk;

import java.util.ArrayList;
import java.util.List;

/**
 * The language server's evaluation (in the language's context, through
 * {@code __nixTruffle.lspEval}): an expression as it would be at an offset of a file, in the
 * scopes around that offset. The file's syntax tree is rebuilt around the expression, keeping what
 * binds names on the way there ({@code let}, recursive sets, {@code with}, functions), and the
 * functions are applied to what {@code args} gives for their arguments: a function of
 * {@code { names, optional }} (formals) or {@code { arg }} (a plain argument), the language
 * server's guess (nixpkgs' {@code pkgs}, a NixOS configuration's {@code config}, ...).
 * Everything else in the file is left out, and all of it is lazy.
 */
public final class Evaluate {
    private Evaluate() {}

    /**
     * {@code expression} (source text) in the scope at {@code offset} of {@code text} (byte
     * strings); or, with {@code expression} null, the file's own expression that starts at
     * {@code offset} ({@code lib} in {@code inherit (lib) mkIf;}), in its scope.
     */
    public static Object at(String path, String text, int offset, String expression, Object args) {
        Source source = Source.newBuilder("nix", text, path).build();
        Expr root = new Parser(source).parseFile();
        Expr target = expression != null ? new Parser(Source.newBuilder("nix", expression, path).build()).parseFile() : startingAt(root, offset);
        if (target == null) throw nixtruffle.runtime.NixException.error("no expression there", null);
        List<Expr> binders = new ArrayList<>();
        collect(root, offset, binders);
        return in(source, path, text, binders, target, args);
    }

    /**
     * The sets of the {@code with}s around {@code offset}, innermost first: each {@code with}'s
     * expression in the scope it is in (lazily: a list of thunks).
     */
    public static Object withs(String path, String text, int offset, Object args) {
        Source source = Source.newBuilder("nix", text, path).build();
        Expr root = new Parser(source).parseFile();
        List<Expr> binders = new ArrayList<>();
        collect(root, offset, binders);
        List<Object> envs = new ArrayList<>();
        for (int i = binders.size() - 1; i >= 0; i--) {
            if (!(binders.get(i) instanceof With w)) continue;
            List<Expr> outside = binders.subList(0, i);
            envs.add(Apply.lazy(new nixtruffle.runtime.Builtin("withEnv", 1, a -> in(source, path, text, outside, w.env(), args)), nixtruffle.runtime.NixNull.INSTANCE));
        }
        return new nixtruffle.runtime.NixList(envs.toArray());
    }

    /** {@code target} inside the scopes of {@code binders} (outermost first), applied to {@code args}. */
    private static Object in(Source source, String path, String text, List<Expr> binders, Expr target, Object args) {
        Expr inner = target;
        for (int i = binders.size() - 1; i >= 0; i--) inner = rebind(binders.get(i), inner);
        Expr fn = new Lambda(ARGS, null, inner, 0);
        NixContext ctx = NixContext.get(null);
        fn = lenient(text, fn, ctx);
        Object f = ctx.language.translate(source, path, fn).call();
        return Thunk.force(Apply.apply(f, args, null));
    }

    private static final String ARGS = "__lspArgs";

    /** The outermost expression that starts at {@code offset}, or null. */
    private static Expr startingAt(Expr e, int offset) {
        if (e.pos() == offset) return e;
        for (Expr c : Scopes.children(e)) {
            Expr found = startingAt(c, offset);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * {@code e} with its undefined variables (a file being edited has some) throwing when used,
     * rather than failing to translate: through a {@code with} around it all, which is where
     * names that are bound nowhere else are looked up.
     */
    private static Expr lenient(String text, Expr e, NixContext ctx) {
        nixtruffle.GlobalScope g = ctx.globalScope();
        java.util.Set<String> globals = new java.util.HashSet<>();
        for (int i = 0; i < g.size(); i++) globals.add(g.name(i));
        java.util.Set<String> undefined = new java.util.TreeSet<>();
        for (Scopes.Use u : Scopes.analyze(text, e, globals).uses) if (u.kind() == Scopes.Kind.UNDEFINED) undefined.add(u.name());
        if (undefined.isEmpty()) return e;
        List<Binding> throwing = new ArrayList<>();
        for (String name : undefined) {
            throwing.add(new Assign(List.of(AttrKey.of(name)), new App(new Var("throw", 0), List.of(str("undefined variable '" + name + "'")), 0), 0));
        }
        return new With(new Attrs(false, throwing, 0), e, 0);
    }

    /** The nodes that bind names, from {@code e} to the node at {@code offset}. */
    private static void collect(Expr e, int offset, List<Expr> out) {
        switch (e) {
            case Lambda l -> out.add(l);
            case Let l -> out.add(l);
            case Attrs a when a.rec() -> out.add(a);
            case With w -> {
                if (offset >= w.body().pos()) {
                    out.add(w);
                    collect(w.body(), offset, out);
                } else {
                    collect(w.env(), offset, out);
                }
                return;
            }
            default -> {}
        }
        Expr in = null;
        for (Expr c : Scopes.children(e)) if (c.pos() <= offset) in = c;
        if (in != null) collect(in, offset, out);
    }

    /** {@code inner} in the scope that {@code binder} makes. */
    private static Expr rebind(Expr binder, Expr inner) {
        return switch (binder) {
            case With w -> new With(w.env(), inner, w.pos());
            case Let l -> new Let(l.bindings(), inner, l.pos());
            // A recursive set's names, as a let's (without dynamic ones, which a let can't have).
            case Attrs a -> new Let(a.bindings().stream().filter(b -> !(b instanceof Assign as && as.path().getFirst().name() == null)).toList(), inner, a.pos());
            case Lambda l -> new App(new Lambda(l.arg(), l.formals(), inner, l.pos()), List.of(arguments(l)), l.pos());
            default -> inner;
        };
    }

    /** What the function gets: {@code args { names = [...]; optional = [...]; }}, or {@code args { arg = "x"; }}. */
    private static Expr arguments(Lambda l) {
        List<Binding> request = new ArrayList<>();
        if (l.formals() != null) {
            List<Expr> names = new ArrayList<>();
            List<Expr> optional = new ArrayList<>();
            for (Formal f : l.formals().formals()) {
                names.add(str(f.name()));
                if (f.fallback() != null) optional.add(str(f.name()));
            }
            request.add(new Assign(List.of(AttrKey.of("names")), new ListE(names, 0), 0));
            request.add(new Assign(List.of(AttrKey.of("optional")), new ListE(optional, 0), 0));
        } else {
            request.add(new Assign(List.of(AttrKey.of("arg")), str(l.arg()), 0));
        }
        // which function it is (the file's own gets the arguments it was called with, if known)
        request.add(new Assign(List.of(AttrKey.of("pos")), new Int(l.pos(), 0), 0));
        return new App(new Var(ARGS, 0), List.of(new Attrs(false, request, 0)), 0);
    }

    private static Expr str(String s) {
        return new Str(List.of(s), 0);
    }
}
