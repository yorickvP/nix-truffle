package nixtruffle;

import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import nixtruffle.builtins.Builtins;
import nixtruffle.nodes.ApplyNode;
import nixtruffle.nodes.AttrKeyNode;
import nixtruffle.nodes.AttrsNode;
import nixtruffle.nodes.CallLevelNode;
import nixtruffle.nodes.ControlNodes;
import nixtruffle.nodes.ControlNodes.WriteSlot;
import nixtruffle.nodes.FunctionNodes;
import nixtruffle.nodes.GlobalReadNode;
import nixtruffle.nodes.HasAttrNode;
import nixtruffle.nodes.InterpolationNode;
import nixtruffle.nodes.LazyCode;
import nixtruffle.nodes.ListNode;
import nixtruffle.nodes.LiteralNodes.Constant;
import nixtruffle.nodes.LiteralNodes.DoubleLiteral;
import nixtruffle.nodes.LiteralNodes.LongLiteral;
import nixtruffle.nodes.MakeThunkNode;
import nixtruffle.nodes.NixNode;
import nixtruffle.nodes.NixRootNode;
import nixtruffle.nodes.OperatorNodes;
import nixtruffle.nodes.OperatorNodesFactory.AddNodeGen;
import nixtruffle.nodes.OperatorNodesFactory.DivNodeGen;
import nixtruffle.nodes.OperatorNodesFactory.EqualNodeGen;
import nixtruffle.nodes.OperatorNodesFactory.LessThanNodeGen;
import nixtruffle.nodes.OperatorNodesFactory.MulNodeGen;
import nixtruffle.nodes.OperatorNodesFactory.SubNodeGen;
import nixtruffle.nodes.ReadRawVarNode;
import nixtruffle.nodes.ReadVarNode;
import nixtruffle.nodes.ReplVarNode;
import nixtruffle.nodes.PathInterpolationNode;
import nixtruffle.nodes.SelectNode;
import nixtruffle.nodes.WithLookupNode;
import nixtruffle.parser.Expr;
import nixtruffle.parser.Expr.*;
import nixtruffle.parser.Expr.Binding.Assign;
import nixtruffle.parser.Expr.Binding.Inherit;
import nixtruffle.parser.Parser;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixLambda;
import nixtruffle.runtime.NixPath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the syntax tree into Truffle nodes, resolving every variable statically.
 *
 * <p>Each lambda body and each thunk body is a separate root ("function"); {@code let}, {@code rec},
 * {@code with} and formals only allocate slots in the enclosing function's frame. Since Nix has no
 * loops, a node runs at most once per frame activation, so one slot per binding is enough.
 *
 * <p>Laziness is decided here, like CppNix's {@code maybeThunk} (and thc's "already evaluated"
 * proofs): constants, lambdas and references to lexical variables are cheap and are never wrapped
 * in a thunk; a variable reference in a lazy position shares the binding's existing thunk.
 *
 * <p>Thunk and lambda bodies are only {@link #check checked} for errors at first, and built when
 * first needed (see {@link LazyCode}): most of them never are.
 */
public final class Translator {
    private final NixLanguage language;
    private final Source source;
    private final String baseDir;
    private final String path;
    /** REPL mode: names bound in the REPL scope, which the root receives as {@code arguments[0]}. */
    private final java.util.Set<String> replNames;
    /** The names in the base environment, resolved before {@code with}. */
    private final GlobalScope globals;
    private int hiddenCounter;
    /** While building a deferred body: it has been checked already, with everything in it. */
    private boolean checked;

    /** A root being built: its nesting level and number of frame slots. */
    private static final class Fn {
        final int level;
        int slots;

        Fn(Fn parent) { this.level = parent == null ? 0 : parent.level + 1; }

        int alloc() { return slots++; }
    }

    /** A lexical scope. {@code withSlot >= 0} marks a {@code with} scope. */
    private record Scope(Scope parent, Fn fn, Map<String, Integer> vars, int withSlot) {}

    private sealed interface Resolved {}
    private record Local(int depth, int slot, Scope scope) implements Resolved {}
    private record WithVar(int[] depths, int[] slots) implements Resolved {}
    /** A name in the base environment: its index in {@link #globals}, or its value if that is the same in every context. */
    private record Global(int index, Object constant) implements Resolved {}
    private record ReplVar(int depth) implements Resolved {}

    /** {@code path} is the file (a byte string) or null; relative paths resolve against {@code baseDir}. */
    public Translator(NixLanguage language, Source source, String path, String baseDir, java.util.Set<String> replNames, GlobalScope globals) {
        this.language = language;
        this.globals = globals;
        this.source = source;
        this.path = path;
        this.replNames = replNames;
        this.baseDir = baseDir;
    }

    public NixRootNode translateFile(Expr e) {
        Fn fn = new Fn(null);
        NixNode body = strict(e, new Scope(null, fn, Map.of(), -1));
        return new NixRootNode(language, descriptor(fn), body, source.getName(), source.createSection(0, source.getLength()));
    }

    // ------------------------------------------------------------ utilities

    private static FrameDescriptor descriptor(Fn fn) {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder(fn.slots);
        builder.addSlots(fn.slots, FrameSlotKind.Object);
        return builder.build();
    }

    private SourceSection section(int pos) {
        int len = source.getLength();
        if (len == 0) return source.createUnavailableSection();
        return source.createSection(Math.max(0, Math.min(pos, len - 1)), 1);
    }

    private NixException error(String message, int pos) {
        return new NixException(message + "\n       at " + Parser.location(source, pos), null);
    }

    /** {@code { file; line; column; }} for a source offset, or null for sources that aren't files. */
    public Object position(int pos) {
        nixtruffle.runtime.Pos p = pos(pos);
        return p == null ? nixtruffle.runtime.NixNull.INSTANCE : p.toValue();
    }

    /** A position in this file; null if the source isn't a file (e.g. an {@code -E} expression). */
    private nixtruffle.runtime.Pos pos(int offset) {
        if (path == null || source.getLength() == 0) return null;
        return new nixtruffle.runtime.Pos(source, path, offset);
    }

    private Resolved resolve(String name, Scope scope, int pos) {
        List<int[]> withs = null;
        for (Scope s = scope; s != null; s = s.parent) {
            int depth = scope.fn.level - s.fn.level;
            if (s.withSlot >= 0) {
                if (withs == null) withs = new ArrayList<>();
                withs.add(new int[] {depth, s.withSlot});
            } else {
                Integer slot = s.vars.get(name);
                if (slot != null) return new Local(depth, slot, s);
            }
        }
        // REPL variables and builtins shadow `with`, exactly like in CppNix where they are lexical scopes.
        if (replNames != null && replNames.contains(name)) return new ReplVar(scope.fn.level);
        int global = globals.indexOf(name);
        if (global >= 0) return new Global(global, globals.constant(global));
        if (withs == null) throw error("undefined variable '" + name + "'", pos);
        int[] depths = new int[withs.size()];
        int[] slots = new int[withs.size()];
        for (int i = 0; i < depths.length; i++) {
            depths[i] = withs.get(i)[0];
            slots[i] = withs.get(i)[1];
        }
        return new WithVar(depths, slots);
    }

    // ------------------------------------------------------ strict positions

    private NixNode strict(Expr e, Scope s) {
        NixNode node = switch (e) {
            case Int i -> new LongLiteral(i.value());
            case Flt f -> new DoubleLiteral(f.value());
            case Str str -> str.isLiteral() ? new Constant(str.literal()) : interpolation(str, s);
            case PathLit p -> new Constant(new NixPath(resolvePath(p)));
            case PathInterp p -> {
                // The first segment keeps its trailing slash; the whole path is canonicalized at the end.
                String first = resolvePath(new PathLit(p.first(), p.pos())) + (p.first().endsWith("/") ? "/" : "");
                NixNode[] parts = new NixNode[p.rest().size()];
                for (int i = 0; i < parts.length; i++) {
                    Object part = p.rest().get(i);
                    parts[i] = part instanceof String lit ? new Constant(lit) : strict((Expr) part, s);
                }
                yield new PathInterpolationNode(first, parts);
            }
            case SearchPath sp -> strict(new App(new Var("__findFile", sp.pos()),
                    List.of(new Var("__nixPath", sp.pos()), new Str(List.of(sp.name()), sp.pos())), sp.pos()), s);
            case Var v -> variable(v, s);
            case CurPos c -> new Constant(position(c.pos()));
            case Select sel -> new SelectNode(strict(sel.target(), s), keys(sel.path(), s),
                    sel.fallback() == null ? null : strict(sel.fallback(), s));
            case HasAttr h -> new HasAttrNode(strict(h.target(), s), keys(h.path(), s));
            case App a -> new ApplyNode(strict(a.fn(), s), lazyAll(a.args(), s));
            case Lambda l -> lambda(l, s, NixLambda.ANONYMOUS);
            case Let l -> let(l, s);
            case Attrs a -> attrs(a, s);
            case ListE l -> new ListNode(lazyAll(l.items(), s));
            case If i -> new ControlNodes.If(strict(i.cond(), s), strict(i.then(), s), strict(i.otherwise(), s));
            case With w -> with(w, s);
            case Assert a -> {
                boolean eq = a.cond() instanceof BinOp b && b.op().equals("==");
                yield new ControlNodes.Assert(strict(a.cond(), s), strict(a.body(), s),
                        eq ? strict(((BinOp) a.cond()).left(), s) : null, eq ? strict(((BinOp) a.cond()).right(), s) : null,
                        () -> nixtruffle.parser.ExprShow.show(a.cond(), baseDir));
            }
            case BinOp b -> binop(b, s);
            case Not n -> new ControlNodes.Not(strict(n.operand(), s));
            case Neg n -> new CallLevelNode(SubNodeGen.create(new LongLiteral(0), strict(n.operand(), s)));
        };
        node.setSourceSection(section(e.pos()));
        return node;
    }

    private NixNode global(Global g) {
        return g.constant() != null ? new Constant(g.constant()) : new GlobalReadNode(globals, g.index());
    }

    private NixNode variable(Var v, Scope s) {
        return switch (resolve(v.name(), s, v.pos())) {
            case Global g -> global(g);
            case Local l -> new ReadVarNode(l.depth(), l.slot());
            case WithVar w -> new WithLookupNode(v.name(), w.depths(), w.slots());
            case ReplVar r -> new ReplVarNode(r.depth(), v.name());
        };
    }

    private NixNode interpolation(Str str, Scope s) {
        NixNode[] parts = new NixNode[str.parts().size()];
        for (int i = 0; i < parts.length; i++) {
            Object part = str.parts().get(i);
            parts[i] = part instanceof String lit ? new Constant(lit) : strict((Expr) part, s);
        }
        return new InterpolationNode(parts);
    }

    private AttrKeyNode[] keys(List<AttrKey> path, Scope s) {
        AttrKeyNode[] out = new AttrKeyNode[path.size()];
        for (int i = 0; i < out.length; i++) {
            AttrKey k = path.get(i);
            out[i] = k.name() != null ? new AttrKeyNode.Static(k.name()) : new AttrKeyNode.Dynamic(strict(k.expr(), s));
        }
        return out;
    }

    private NixNode binop(BinOp b, Scope s) {
        NixNode l = strict(b.left(), s);
        NixNode r = strict(b.right(), s);
        return switch (b.op()) {
            case "+" -> AddNodeGen.create(l, r);
            // Primop calls in CppNix (__sub, __lessThan, ...), a max-call-depth level with their operands.
            case "-" -> new CallLevelNode(SubNodeGen.create(l, r));
            case "*" -> new CallLevelNode(MulNodeGen.create(l, r));
            case "/" -> new CallLevelNode(DivNodeGen.create(l, r));
            case "<" -> new CallLevelNode(LessThanNodeGen.create(l, r));
            case ">" -> new CallLevelNode(LessThanNodeGen.create(r, l));
            case "<=" -> new ControlNodes.Not(new CallLevelNode(LessThanNodeGen.create(r, l)));
            case ">=" -> new ControlNodes.Not(new CallLevelNode(LessThanNodeGen.create(l, r)));
            case "==" -> EqualNodeGen.create(l, r);
            case "!=" -> new ControlNodes.Not(EqualNodeGen.create(l, r));
            case "&&" -> new ControlNodes.And(l, r);
            case "||" -> new ControlNodes.Or(l, r);
            case "->" -> new ControlNodes.Impl(l, r);
            case "++" -> new OperatorNodes.Concat(l, r);
            case "//" -> new OperatorNodes.Update(l, r);
            default -> throw error("unknown operator " + b.op(), b.pos());
        };
    }

    private String resolvePath(PathLit p) {
        String text = p.text();
        if (text.startsWith("/")) return NixPath.canonicalize(text);
        if (text.startsWith("~/")) return NixPath.canonicalize(nixtruffle.runtime.Bytes.fromJava(System.getProperty("user.home")) + text.substring(1));
        return NixPath.canonicalize(baseDir + "/" + text);
    }

    // -------------------------------------------------------- lazy positions

    private NixNode[] lazyAll(List<Expr> exprs, Scope s) {
        NixNode[] out = new NixNode[exprs.size()];
        for (int i = 0; i < out.length; i++) out[i] = lazy(exprs.get(i), s);
        return out;
    }

    /** A node that produces the value of {@code e} without evaluating it (unless that is free). */
    private NixNode lazy(Expr e, Scope s) {
        switch (e) {
            case Int i -> { return strict(e, s); }
            case Flt f -> { return strict(e, s); }
            case PathLit p -> { return strict(e, s); }
            case Lambda l -> { return strict(e, s); }
            case Str str when str.isLiteral() -> { return strict(e, s); }
            case CurPos c -> { return strict(e, s); }
            case Var v -> {
                switch (resolve(v.name(), s, v.pos())) {
                    case Global g -> { return global(g); }
                    case Local l -> { return new ReadRawVarNode(l.depth(), l.slot()); }
                    case WithVar w -> { return thunk(e, s); }
                    case ReplVar r -> { return thunk(e, s); }
                }
            }
            default -> { return thunk(e, s); }
        }
    }

    /**
     * Lazy value of a binding in a recursive group ({@code let}, {@code rec}, formals). A bare
     * reference to another member of the same group cannot share its slot, because that slot may
     * not be initialized yet ({@code let a = b; b = 1;}), so it gets a thunk.
     */
    private NixNode lazyBinding(Expr e, Scope group, String name) {
        if (e instanceof Lambda l) return lambda(l, group, name);
        if (e instanceof Var v && resolve(v.name(), group, v.pos()) instanceof Local l && l.scope() == group) return thunk(e, group);
        return lazy(e, group);
    }

    private NixNode thunk(Expr e, Scope s) {
        if (!checked) check(e, s);
        return new MakeThunkNode(new Deferred(() -> {
            Fn fn = new Fn(s.fn);
            NixNode body = strict(e, new Scope(s, fn, Map.of(), -1));
            SourceSection sec = section(e.pos());
            String name = "thunk@" + source.getName() + ":" + sec.getStartLine() + ":" + sec.getStartColumn();
            return new NixRootNode(language, descriptor(fn), body, name, sec).getCallTarget();
        }));
    }

    /** A body built later by this translator, which has checked it already. */
    private final class Deferred extends LazyCode {
        private java.util.function.Supplier<com.oracle.truffle.api.RootCallTarget> body;

        Deferred(java.util.function.Supplier<com.oracle.truffle.api.RootCallTarget> body) {
            this.body = body;
        }

        @Override
        protected com.oracle.truffle.api.RootCallTarget build() {
            synchronized (Translator.this) {
                boolean was = checked;
                checked = true;
                try {
                    return body.get();
                } finally {
                    checked = was;
                    body = null;
                }
            }
        }
    }

    // ----------------------------------------------------- binding constructs

    private NixNode lambda(Lambda l, Scope s, String name) {
        if (!checked) checkLambda(l, s);
        NixLambda.Info info;
        if (l.formals() == null) {
            info = new NixLambda.Info(name, l.arg(), null, null, null, false, false);
        } else {
            List<Formal> formals = l.formals().formals();
            String[] names = new String[formals.size()];
            boolean[] hasDefault = new boolean[names.length];
            Object[] formalPositions = new Object[names.length];
            for (int i = 0; i < names.length; i++) {
                names[i] = formals.get(i).name();
                hasDefault[i] = formals.get(i).fallback() != null;
                formalPositions[i] = pos(formals.get(i).pos());
            }
            info = new NixLambda.Info(name, l.arg(), names, hasDefault, formalPositions, true, l.formals().ellipsis());
        }
        return new FunctionNodes.Lambda(new Deferred(() -> lambdaBody(l, s, name)), info);
    }

    private com.oracle.truffle.api.RootCallTarget lambdaBody(Lambda l, Scope s, String name) {
        Fn fn = new Fn(s.fn);
        Map<String, Integer> vars = new HashMap<>();
        Scope ls = new Scope(s, fn, vars, -1);
        NixNode prologue;
        if (l.formals() == null) {
            int slot = fn.alloc();
            vars.put(l.arg(), slot);
            prologue = new FunctionNodes.BindArg(slot);
        } else {
            int argSlot = -1;
            if (l.arg() != null) {
                argSlot = fn.alloc();
                vars.put(l.arg(), argSlot);
            }
            List<Formal> formals = l.formals().formals();
            String[] names = new String[formals.size()];
            int[] slots = new int[names.length];
            for (int i = 0; i < names.length; i++) {
                names[i] = formals.get(i).name();
                slots[i] = fn.alloc();
                vars.put(names[i], slots[i]);
            }
            int[] defaultIndex = new int[names.length];
            List<NixNode> defaults = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                Expr fallback = formals.get(i).fallback();
                defaultIndex[i] = fallback == null ? -1 : defaults.size();
                if (fallback != null) defaults.add(lazyBinding(fallback, ls, names[i]));
            }
            prologue = new FunctionNodes.BindFormals(name, names, slots, defaultIndex, defaults.toArray(NixNode[]::new),
                    l.formals().ellipsis(), argSlot);
        }
        NixNode body = strict(l.body(), ls);
        NixNode full = new FunctionNodes.Body(prologue, body);
        return new NixRootNode(language, descriptor(fn), full, name, section(l.pos())).getCallTarget();
    }

    private NixNode with(With w, Scope s) {
        int slot = s.fn.alloc();
        NixNode env = lazy(w.env(), s);
        NixNode body = strict(w.body(), new Scope(s, s.fn, Map.of(), slot));
        return new ControlNodes.With(slot, env, body);
    }

    private NixNode let(Let let, Scope s) {
        Normalized nb = normalize(let.bindings());
        if (!nb.dynamic.isEmpty()) throw error("dynamic attributes not allowed in let", let.pos());
        Map<String, Integer> vars = new HashMap<>();
        Scope ls = new Scope(s, s.fn, vars, -1);
        List<WriteSlot> writes = bindGroup(nb, s, ls, vars);
        return new ControlNodes.Let(writes.toArray(WriteSlot[]::new), strict(let.body(), ls));
    }

    /** Allocates slots for a recursive group and returns the writes that initialize them. */
    private List<WriteSlot> bindGroup(Normalized nb, Scope outer, Scope group, Map<String, Integer> vars) {
        for (String name : nb.statics.keySet()) vars.put(name, outer.fn.alloc());
        for (Hidden h : nb.hidden) vars.put(h.name, outer.fn.alloc());
        List<WriteSlot> writes = new ArrayList<>();
        for (Hidden h : nb.hidden) writes.add(new WriteSlot(vars.get(h.name), lazyBinding(h.expr, group, h.name)));
        for (Map.Entry<String, Entry> e : nb.statics.entrySet()) {
            writes.add(new WriteSlot(vars.get(e.getKey()), entryValue(e.getValue(), e.getKey(), outer, group)));
        }
        return writes;
    }

    private NixNode entryValue(Entry entry, String name, Scope outer, Scope scope) {
        // `inherit x;` always refers to the enclosing scope, even inside `rec`/`let`.
        if (entry.inheritVar) return lazy(new Var(name, entry.pos), outer);
        return lazyBinding(entry.value(), scope, name);
    }

    private NixNode attrs(Attrs a, Scope s) {
        Normalized nb = normalize(a.bindings());
        String[] keys = nb.statics.keySet().stream().sorted().toArray(String[]::new);
        NixNode[] values = new NixNode[keys.length];
        NixNode[] dynKeys = new NixNode[nb.dynamic.size()];
        NixNode[] dynValues = new NixNode[nb.dynamic.size()];
        if (a.rec()) {
            Map<String, Integer> vars = new HashMap<>();
            Scope rs = new Scope(s, s.fn, vars, -1);
            List<WriteSlot> writes = bindGroup(nb, s, rs, vars);
            for (int i = 0; i < keys.length; i++) values[i] = new ReadRawVarNode(0, vars.get(keys[i]));
            for (int i = 0; i < dynKeys.length; i++) {
                dynKeys[i] = strict(nb.dynamic.get(i)[0], rs);
                dynValues[i] = lazy(nb.dynamic.get(i)[1], rs);
            }
            return new ControlNodes.Let(writes.toArray(WriteSlot[]::new), new AttrsNode(keys, values, dynKeys, dynValues, positions(nb, keys)));
        }
        Scope vs = s;
        List<WriteSlot> writes = new ArrayList<>();
        if (!nb.hidden.isEmpty()) {
            Map<String, Integer> vars = new HashMap<>();
            vs = new Scope(s, s.fn, vars, -1);
            for (Hidden h : nb.hidden) {
                int slot = s.fn.alloc();
                vars.put(h.name, slot);
                writes.add(new WriteSlot(slot, lazy(h.expr, s)));
            }
        }
        for (int i = 0; i < keys.length; i++) values[i] = entryValue(nb.statics.get(keys[i]), keys[i], s, vs);
        for (int i = 0; i < dynKeys.length; i++) {
            dynKeys[i] = strict(nb.dynamic.get(i)[0], s);
            dynValues[i] = lazy(nb.dynamic.get(i)[1], s);
        }
        AttrsNode node = new AttrsNode(keys, values, dynKeys, dynValues, positions(nb, keys));
        return writes.isEmpty() ? node : new ControlNodes.Let(writes.toArray(WriteSlot[]::new), node);
    }

    /** The positions of an attribute set literal's static attributes, then its dynamic ones. */
    private Object[][] positions(Normalized nb, String[] keys) {
        Object[] statics = new Object[keys.length];
        for (int i = 0; i < keys.length; i++) statics[i] = pos(nb.statics.get(keys[i]).pos);
        Object[] dynamic = new Object[nb.dynamicPos.size()];
        for (int i = 0; i < dynamic.length; i++) dynamic[i] = pos(nb.dynamicPos.get(i));
        return new Object[][] {keys.length == 0 || statics[0] == null ? null : statics, dynamic};
    }

    // --------------------------------------------------------------- checking

    /**
     * Throws the error translating {@code e} would (an undefined variable, an attribute defined
     * twice, a duplicate formal), in the same order, without building anything. Deferred bodies
     * are checked when they are deferred, so a file's errors are found when it is parsed, as in
     * CppNix, although most of it is never built.
     */
    private void check(Expr e, Scope s) {
        switch (e) {
            case Int i -> {}
            case Flt f -> {}
            case PathLit p -> {}
            case CurPos c -> {}
            case Str str -> checkParts(str.parts(), s);
            case PathInterp p -> checkParts(p.rest(), s);
            case SearchPath sp -> {
                checkVar("__findFile", s, sp.pos());
                checkVar("__nixPath", s, sp.pos());
            }
            case Var v -> checkVar(v.name(), s, v.pos());
            case Select sel -> {
                check(sel.target(), s);
                checkKeys(sel.path(), s);
                if (sel.fallback() != null) check(sel.fallback(), s);
            }
            case HasAttr h -> {
                check(h.target(), s);
                checkKeys(h.path(), s);
            }
            case App a -> {
                check(a.fn(), s);
                for (Expr arg : a.args()) check(arg, s);
            }
            case Lambda l -> checkLambda(l, s);
            case Let l -> checkLet(l, s);
            case Attrs a -> checkAttrs(a, s);
            case ListE l -> {
                for (Expr item : l.items()) check(item, s);
            }
            case If i -> {
                check(i.cond(), s);
                check(i.then(), s);
                check(i.otherwise(), s);
            }
            case With w -> {
                check(w.env(), s);
                check(w.body(), new Scope(s, s.fn, Map.of(), 0));
            }
            case Assert a -> {
                check(a.cond(), s);
                check(a.body(), s);
            }
            case BinOp b -> {
                check(b.left(), s);
                check(b.right(), s);
            }
            case Not n -> check(n.operand(), s);
            case Neg n -> check(n.operand(), s);
        }
    }

    /** What {@link #resolve} checks: that the name is bound, or that a {@code with} may bind it. */
    private void checkVar(String name, Scope scope, int pos) {
        boolean with = false;
        for (Scope s = scope; s != null; s = s.parent) {
            if (s.withSlot >= 0) {
                with = true;
            } else if (s.vars.containsKey(name)) {
                return;
            }
        }
        if (with || replNames != null && replNames.contains(name) || globals.indexOf(name) >= 0) return;
        throw error("undefined variable '" + name + "'", pos);
    }

    private void checkParts(List<Object> parts, Scope s) {
        for (Object part : parts) if (part instanceof Expr x) check(x, s);
    }

    private void checkKeys(List<AttrKey> path, Scope s) {
        for (AttrKey k : path) if (k.name() == null) check(k.expr(), s);
    }

    private void checkLambda(Lambda l, Scope s) {
        Map<String, Integer> vars = new HashMap<>();
        Scope ls = new Scope(s, new Fn(s.fn), vars, -1);
        if (l.formals() == null) {
            vars.put(l.arg(), 0);
        } else {
            if (l.arg() != null) vars.put(l.arg(), 0);
            for (Formal f : l.formals().formals()) {
                if (vars.containsKey(f.name())) throw error("duplicate formal function argument '" + f.name() + "'", l.pos());
                vars.put(f.name(), 0);
            }
            for (Formal f : l.formals().formals()) if (f.fallback() != null) check(f.fallback(), ls);
        }
        check(l.body(), ls);
    }

    private void checkLet(Let let, Scope s) {
        Normalized nb = normalize(let.bindings());
        if (!nb.dynamic.isEmpty()) throw error("dynamic attributes not allowed in let", let.pos());
        Map<String, Integer> vars = new HashMap<>();
        Scope ls = new Scope(s, s.fn, vars, -1);
        checkGroup(nb, s, ls, vars);
        check(let.body(), ls);
    }

    private void checkGroup(Normalized nb, Scope outer, Scope group, Map<String, Integer> vars) {
        for (String name : nb.statics.keySet()) vars.put(name, 0);
        for (Hidden h : nb.hidden) vars.put(h.name, 0);
        for (Hidden h : nb.hidden) check(h.expr, group);
        for (Map.Entry<String, Entry> e : nb.statics.entrySet()) checkEntry(e.getValue(), e.getKey(), outer, group);
    }

    private void checkEntry(Entry entry, String name, Scope outer, Scope scope) {
        if (entry.inheritVar) {
            checkVar(name, outer, entry.pos);
        } else {
            check(entry.value(), scope);
        }
    }

    private void checkAttrs(Attrs a, Scope s) {
        Normalized nb = normalize(a.bindings());
        if (a.rec()) {
            Map<String, Integer> vars = new HashMap<>();
            Scope rs = new Scope(s, s.fn, vars, -1);
            checkGroup(nb, s, rs, vars);
            for (Expr[] d : nb.dynamic) {
                check(d[0], rs);
                check(d[1], rs);
            }
            return;
        }
        Scope vs = s;
        if (!nb.hidden.isEmpty()) {
            Map<String, Integer> vars = new HashMap<>();
            vs = new Scope(s, s.fn, vars, -1);
            for (Hidden h : nb.hidden) {
                vars.put(h.name, 0);
                check(h.expr, s);
            }
        }
        for (String key : nb.statics.keySet().stream().sorted().toList()) checkEntry(nb.statics.get(key), key, s, vs);
        for (Expr[] d : nb.dynamic) {
            check(d[0], s);
            check(d[1], s);
        }
    }

    // ------------------------------------------------ binding normalization

    /** A static attribute: a plain value, a nested attrset built from paths, or {@code inherit x}. */
    private static final class Entry {
        final int pos;
        Expr leaf;
        List<Binding> nested;
        boolean inheritVar;

        Entry(int pos) { this.pos = pos; }

        Expr value() { return leaf != null ? leaf : new Attrs(false, nested, pos); }
    }

    /** {@code inherit (e) ...}: {@code e} is evaluated once, into a hidden variable. */
    private record Hidden(String name, Expr expr) {}

    private static final class Normalized {
        final LinkedHashMap<String, Entry> statics = new LinkedHashMap<>();
        final List<Expr[]> dynamic = new ArrayList<>();
        final List<Integer> dynamicPos = new ArrayList<>();
        final List<Hidden> hidden = new ArrayList<>();
    }

    /** Groups {@code a.b = 1; a.c = 2;} into nested sets and expands {@code inherit}. */
    private Normalized normalize(List<Binding> bindings) {
        Normalized out = new Normalized();
        for (Binding b : bindings) {
            switch (b) {
                case Inherit inh -> {
                    String hidden = null;
                    if (inh.from() != null) {
                        hidden = "\0inherit" + hiddenCounter++;
                        out.hidden.add(new Hidden(hidden, inh.from()));
                    }
                    for (int n = 0; n < inh.names().size(); n++) {
                        String name = inh.names().get(n);
                        if (out.statics.containsKey(name)) throw error("attribute '" + name + "' already defined", inh.pos());
                        Entry entry = new Entry(inh.namePos().get(n));
                        if (hidden == null) {
                            entry.inheritVar = true;
                        } else {
                            entry.leaf = new Select(new Var(hidden, inh.pos()), List.of(AttrKey.of(name)), null, inh.pos());
                        }
                        out.statics.put(name, entry);
                    }
                }
                case Assign a -> {
                    AttrKey first = a.path().get(0);
                    List<AttrKey> rest = a.path().subList(1, a.path().size());
                    if (first.name() == null) {
                        Expr value = rest.isEmpty() ? a.value() : new Attrs(false, List.of(new Assign(rest, a.value(), a.pos())), a.pos());
                        out.dynamic.add(new Expr[] {first.expr(), value});
                        out.dynamicPos.add(a.pos());
                        continue;
                    }
                    Entry existing = out.statics.get(first.name());
                    List<Binding> more;
                    if (rest.isEmpty()) {
                        more = a.value() instanceof Attrs av && !av.rec() ? av.bindings() : null;
                    } else {
                        more = List.of(new Assign(rest, a.value(), a.pos()));
                    }
                    if (existing == null) {
                        Entry entry = new Entry(a.pos());
                        if (more != null) {
                            entry.nested = new ArrayList<>(more);
                        } else {
                            entry.leaf = a.value();
                        }
                        out.statics.put(first.name(), entry);
                    } else if (existing.nested != null && more != null) {
                        existing.nested.addAll(more);
                    } else {
                        throw error("attribute '" + first.name() + "' already defined", a.pos());
                    }
                }
            }
        }
        return out;
    }
}
