package nixtruffle.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleContext;
import nixtruffle.NixContext;
import nixtruffle.runtime.Bytes;
import nixtruffle.runtime.Derivations;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixFunction;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.NixNull;
import nixtruffle.runtime.NixPath;
import nixtruffle.runtime.NixString;
import nixtruffle.runtime.Parallel;
import nixtruffle.runtime.Thunk;
import nixtruffle.runtime.Values;
import org.pkl.core.DataSize;
import org.pkl.core.Duration;
import org.pkl.core.Evaluator;
import org.pkl.core.EvaluatorBuilder;
import org.pkl.core.ModuleSchema;
import org.pkl.core.ModuleSource;
import org.pkl.core.PClass;
import org.pkl.core.PModule;
import org.pkl.core.PObject;
import org.pkl.core.PType;
import org.pkl.core.Pair;
import org.pkl.core.PklException;
import org.pkl.core.SecurityManager;
import org.pkl.core.SecurityManagerException;
import org.pkl.core.SecurityManagers;
import org.pkl.core.TypeAlias;
import org.pkl.core.module.ModuleKey;
import org.pkl.core.module.ModuleKeyFactory;
import org.pkl.core.module.ResolvedModuleKey;
import org.pkl.core.module.ResolvedModuleKeys;
import org.pkl.core.resource.ResourceReader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Pkl (pkl-lang.org) and Nix, through Pkl's Java API (pkl-core, whose Truffle language runs on
 * ours):
 * <ul>
 *   <li>{@code import ./config.pkl} evaluates a Pkl module to a Nix value: objects and mappings
 *       become attribute sets, listings lists, durations and data sizes {@code { value, unit }}.
 *   <li>{@code builtins.pkl { module = ./schema.pkl; amend = { ... }; }} amends the module with Nix
 *       values: Pkl checks them against the module's types and constraints, fills in defaults,
 *       and recomputes what depends on them.
 *   <li>Pkl code reads Nix through {@code nix:} URIs into the {@code nix} attribute set given to
 *       {@code builtins.pkl}: {@code import "nix:pkgs" as pkgs} is a module whose properties are
 *       the attribute set's, each evaluated (in Nix) only when used; {@code read("nix:a.b")} is
 *       a value as text (derivations as their output path).
 * </ul>
 * Strings that went from Nix to Pkl with a string context (store paths) get it back when they, or
 * strings containing them, come back.
 */
final class Pkl {
    private Pkl() {}

    /** One evaluation: the {@code nix:} scope, and the contexts of the strings handed to Pkl. */
    private static final class Call {
        final NixContext ctx = NixContext.get(null);
        final TruffleContext truffleContext = ctx.env.getContext();
        final NixAttrs scope;
        /** Strings with a context that Pkl got, and their contexts. */
        final Map<String, Set<String>> contexts = new HashMap<>();

        Call(NixAttrs scope) {
            this.scope = scope;
        }

        /** Runs Nix code from a Pkl callback, where Pkl's context is the entered one. */
        <T> T inNix(java.util.function.Supplier<T> body) {
            Object prev = truffleContext.enter(null);
            try {
                return body.get();
            } finally {
                truffleContext.leave(null, prev);
            }
        }

        Evaluator evaluator() {
            boolean pure = ctx.settings.getBool("pure-eval");
            List<Pattern> modules = new ArrayList<>(List.of(Pattern.compile("pkl:"), Pattern.compile("file:"), Pattern.compile("modulepath:"),
                    Pattern.compile("repl:"), Pattern.compile("nix:")));
            List<Pattern> resources = new ArrayList<>(List.of(Pattern.compile("prop:"), Pattern.compile("file:"), Pattern.compile("modulepath:"),
                    Pattern.compile("nix:")));
            if (!pure) {
                // Pkl packages and URLs: fetched by Pkl, not locked like Nix's fetchers.
                modules.addAll(List.of(Pattern.compile("https:"), Pattern.compile("package:"), Pattern.compile("projectpackage:")));
                resources.addAll(List.of(Pattern.compile("env:"), Pattern.compile("https:"), Pattern.compile("package:"), Pattern.compile("projectpackage:")));
            }
            SecurityManager standard = SecurityManagers.standard(modules, resources, SecurityManagers.defaultTrustLevels, null);
            EvaluatorBuilder b = EvaluatorBuilder.preconfigured()
                    .setSecurityManager(new Access(ctx, standard))
                    .addModuleKeyFactory(new NixModules(this))
                    .addResourceReader(new NixResources(this));
            if (pure) b.setEnvironmentVariables(Map.of());
            return b.build();
        }
    }

    /**
     * Pkl's standard checks (the allowed URI schemes), and Nix's for the files Pkl reads: in pure
     * evaluation, only those that Nix may read.
     */
    private record Access(NixContext ctx, SecurityManager standard) implements SecurityManager {
        private void checkFile(URI uri) throws SecurityManagerException {
            if (!"file".equals(uri.getScheme())) return;
            try {
                ctx.checkAccess(Bytes.fromJava(Path.of(uri).toString()));
            } catch (NixException e) {
                throw new SecurityManagerException(Bytes.toJava(e.getMessage()));
            }
        }

        @Override
        public void checkResolveModule(URI uri) throws SecurityManagerException {
            standard.checkResolveModule(uri);
            checkFile(uri);
        }

        @Override
        public void checkImportModule(URI importing, URI imported) throws SecurityManagerException {
            standard.checkImportModule(importing, imported);
        }

        @Override
        public void checkReadResource(URI uri) throws SecurityManagerException {
            standard.checkReadResource(uri);
            checkFile(uri);
        }

        @Override
        public void checkResolveResource(URI uri) throws SecurityManagerException {
            standard.checkResolveResource(uri);
        }
    }

    // ------------------------------------------------------------ entry points

    /** {@code import ./x.pkl}. */
    @TruffleBoundary
    static Object importFile(String file) {
        Parallel.mainOnly(null, "a Pkl import");
        Call call = new Call(null);
        try (Evaluator e = call.evaluator()) {
            return toNix(e.evaluate(ModuleSource.path(Path.of(Bytes.toJava(file)))), call);
        } catch (PklException e) {
            throw error(e);
        }
    }

    /**
     * {@code builtins.pkl { module | text, amend ? { }, expression ? null, output ? false,
     * defaults ? true, nix ? { } }}: the module's value (or the expression's), or with
     * {@code output} its rendered output (its {@code output} block's renderer: JSON, YAML, plist,
     * ...) as a string. With {@code defaults = false}, only what the module's files set, lazily
     * ({@link Lazy}).
     */
    @TruffleBoundary
    static Object eval(NixAttrs args) {
        Object scope = args.get("nix");
        Call call = new Call(scope == null ? null : Values.attrs(scope));
        Object module = args.get("module");
        Object text = args.get("text");
        if ((module == null) == (text == null)) throw NixException.error("builtins.pkl: give either 'module' (a path) or 'text'", null);
        Object amend = args.get("amend");
        Object expression = args.get("expression");
        Object output = args.get("output");
        Object defaults = args.get("defaults");
        if (defaults != null && !Values.bool(defaults)) {
            if (amend != null || expression != null || output != null) {
                throw NixException.error("builtins.pkl: 'defaults = false' doesn't go with 'amend', 'expression' or 'output'", null);
            }
            return Lazy.module(call, source(module, text));
        }
        try (Evaluator e = call.evaluator()) {
            ModuleSource source = source(module, text);
            if (amend != null) {
                if (module == null) throw NixException.error("builtins.pkl: 'amend' needs a 'module'", null);
                source = ModuleSource.text(amending(e, source, Values.attrs(amend), call));
            }
            if (output != null && Values.bool(output)) return string(e.evaluateOutputText(source), call);
            Object result = expression == null ? e.evaluate(source) : e.evaluateExpression(source, Bytes.toJava(Values.coerce(expression, false, false, new TreeSet<>(), null)));
            return toNix(result, call);
        } catch (PklException e) {
            throw error(e);
        }
    }

    private static ModuleSource source(Object module, Object text) {
        if (module == null) return ModuleSource.text(Bytes.toJava(Values.coerce(text, false, false, new TreeSet<>(), null)));
        String path = Bytes.toJava(Values.coerce(module, false, false, new TreeSet<>(), null));
        NixContext.get(null).checkAccess(Bytes.fromJava(path));
        return ModuleSource.path(Path.of(path));
    }

    private static final Pattern FRAME = Pattern.compile("^at (.*) \\((.*)\\)$");

    /**
     * Pkl's error, without the Nix frames that its stack trace goes on with (they're on the same
     * Truffle stack; Nix sources have {@code truffle:} URIs there), and with Pkl's top frame named
     * after its module rather than the Nix code that called it.
     */
    private static NixException error(PklException e) {
        String[] lines = e.getMessage().replace("–– Pkl Error ––\n", "").strip().split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int keep = lines.length;
        for (int i = 0; i < lines.length; i++) {
            var m = FRAME.matcher(lines[i]);
            if (m.matches() && m.group(2).startsWith("truffle:")) {
                // Drop the frame's excerpt too: back to the blank line before it.
                keep = i;
                while (keep > 0 && !lines[keep - 1].isEmpty()) keep--;
                break;
            }
        }
        for (int i = 0; i < keep; i++) {
            var m = FRAME.matcher(lines[i]);
            String line = lines[i];
            if (m.matches() && !m.group(2).startsWith("truffle:") && (m.group(1).startsWith("thunk@") || m.group(1).contains("/") || m.group(1).startsWith("«"))) {
                String uri = m.group(2);
                String module = uri.substring(uri.lastIndexOf('/') + 1).replaceFirst("\\.pkl$", "");
                line = "at " + module + " (" + uri + ")";
            }
            sb.append(line).append('\n');
        }
        return NixException.error("Pkl: " + Bytes.fromJava(sb.toString().strip()), null);
    }

    /** A module that amends {@code base} with the attributes, typed by the base's properties. */
    private static String amending(Evaluator e, ModuleSource base, NixAttrs values, Call call) {
        ModuleSchema schema = e.evaluateSchema(base);
        Map<String, PClass.Property> properties = schema.getModuleClass().getAllProperties();
        StringBuilder sb = new StringBuilder("amends ").append(literal(base.getUri().toString())).append("\n\n");
        for (int i = 0; i < values.size(); i++) {
            String name = Bytes.toJava(values.keys[i]);
            PClass.Property p = properties.get(name);
            sb.append(identifier(name)).append(" = ");
            render(values.forceAt(i), p == null ? PType.UNKNOWN : p.getType(), sb, call);
            sb.append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ Pkl -> Nix

    private static Object toNix(Object v, Call call) {
        if (v == null || v instanceof org.pkl.core.PNull) return NixNull.INSTANCE;
        if (v instanceof String s) return string(s, call);
        if (v instanceof Boolean || v instanceof Long || v instanceof Double) return v;
        if (v instanceof Integer i) return (long) i;
        if (v instanceof Duration d) return unit(d.getValue(), d.getUnit().toString());
        if (v instanceof DataSize d) return unit(d.getValue(), d.getUnit().toString());
        if (v instanceof byte[] b) return Bytes.of(b);
        if (v instanceof Pair<?, ?> p) return new NixList(new Object[] {toNix(p.getFirst(), call), toNix(p.getSecond(), call)});
        if (v instanceof Collection<?> c) {
            Object[] items = new Object[c.size()];
            int i = 0;
            for (Object o : c) items[i++] = toNix(o, call);
            return new NixList(items);
        }
        if (v instanceof Map<?, ?> m) {
            if (m.keySet().stream().allMatch(k -> k instanceof String)) {
                TreeMap<String, Object> out = new TreeMap<>();
                m.forEach((k, x) -> out.put(Bytes.fromJava((String) k), toNix(x, call)));
                return NixAttrs.fromMap(out);
            }
            List<Object> entries = new ArrayList<>();
            m.forEach((k, x) -> entries.add(NixAttrs.fromMap(new TreeMap<>(Map.of("key", toNix(k, call), "value", toNix(x, call))))));
            return new NixList(entries.toArray());
        }
        // A module of a Nix value (import("nix:...").value, like a derivation): that value.
        if (v instanceof PModule m && "nix".equals(m.getModuleUri().getScheme())) return lookUp(call, path(m.getModuleUri()));
        if (v instanceof PObject o) {
            Map<String, Object> props = o.getProperties();
            // A Dynamic object with only elements: a list.
            boolean elements = !props.isEmpty();
            int n = 0;
            for (String k : props.keySet()) elements &= k.equals(Integer.toString(n++));
            if (elements) return toNix(new ArrayList<>(props.values()), call);
            TreeMap<String, Object> out = new TreeMap<>();
            props.forEach((k, x) -> out.put(Bytes.fromJava(k), toNix(x, call)));
            return NixAttrs.fromMap(out);
        }
        if (v instanceof PClass c) return string(c.getQualifiedName(), call);
        if (v instanceof TypeAlias a) return string(a.getQualifiedName(), call);
        if (v instanceof Pattern p) return string(p.pattern(), call);
        return string(v.toString(), call);
    }

    private static NixAttrs unit(double value, String unit) {
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("value", value == Math.rint(value) && Math.abs(value) < 1e15 ? (Object) (long) value : (Object) value);
        m.put("unit", unit);
        return NixAttrs.fromMap(m);
    }

    /** A string from Pkl, with the contexts of the Nix strings it contains. */
    private static Object string(String s, Call call) {
        Set<String> context = new TreeSet<>();
        call.contexts.forEach((str, c) -> {
            if (s.contains(str)) context.addAll(c);
        });
        String bytes = Bytes.fromJava(s);
        return context.isEmpty() ? bytes : NixString.make(bytes, context);
    }

    // ------------------------------------------------------------ Pkl -> Nix, lazily

    /**
     * {@code builtins.pkl { ...; defaults = false; }}: a module as the members its files set, not
     * those its schema (the module at the root of its amends chain, or pkl:base) defines (an
     * object's own members are set, though, also when a function of the schema made it), like
     * definitions of NixOS options: a property set to null or to an empty listing is there, one
     * that isn't set isn't. Objects are attribute sets (lists for listings), whose names are known
     * without evaluating anything; each value is a thunk that Pkl evaluates (and checks) when Nix
     * uses it, so Pkl can use Nix values that depend on the result's names (a NixOS
     * configuration's {@code pkgs}, through {@code nix:}).
     *
     * <p>This reads Pkl's objects (pkl-core's runtime classes), and evaluates in the evaluator's
     * context through its private {@code doEvaluate}, which the Java API doesn't offer. The
     * evaluator stays open until the values are unreachable.
     */
    private static final class Lazy {
        private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();
        private static java.lang.reflect.Method doEvaluate, doEvaluateModule;

        /** Reads a member: the builtin of the thunks. Named "pkl" for {@link nixtruffle.runtime.Builtin#mainOnly}. */
        private static final nixtruffle.runtime.Builtin READ = new nixtruffle.runtime.Builtin("pkl", 1, a -> ((Member) a[0]).read());

        private record Member(Lazy lazy, org.pkl.core.runtime.VmObjectLike owner, Object key) {
            Object read() {
                return lazy.value(lazy.evaluate(() -> org.pkl.core.runtime.VmUtils.readMember(owner, key)));
            }
        }

        final Call call;
        final Evaluator evaluator;
        /** The schema's source: its members are defaults. */
        final com.oracle.truffle.api.source.Source schema;

        private Lazy(Call call, Evaluator evaluator, com.oracle.truffle.api.source.Source schema) {
            this.call = call;
            this.evaluator = evaluator;
            this.schema = schema;
        }

        static Object module(Call call, ModuleSource source) {
            Evaluator e = call.evaluator();
            try {
                org.pkl.core.runtime.VmTyped module = invoke(e, doEvaluateModule(), source, (java.util.function.Function<org.pkl.core.runtime.VmTyped, Object>) m -> m);
                org.pkl.core.runtime.VmTyped root = module;
                while (root.getModuleInfo().isAmend() && root.getParent() != null) root = root.getParent();
                Lazy lazy = new Lazy(call, e, root.getModuleInfo().getSourceSection().getSource());
                CLEANER.register(lazy, e::close);
                return lazy.object(module);
            } catch (RuntimeException | Error x) {
                e.close();
                throw x;
            }
        }

        <T> T evaluate(java.util.function.Supplier<T> body) {
            return invoke(evaluator, doEvaluate(), body);
        }

        @SuppressWarnings("unchecked")
        private static <T> T invoke(Evaluator e, java.lang.reflect.Method m, Object... args) {
            try {
                return (T) m.invoke(e, args);
            } catch (java.lang.reflect.InvocationTargetException x) {
                Throwable c = x.getCause();
                if (c instanceof PklException p) throw error(p);
                if (c instanceof RuntimeException r) throw r;
                if (c instanceof Error r) throw r;
                throw new IllegalStateException(c);
            } catch (IllegalAccessException x) {
                throw new IllegalStateException(x);
            }
        }

        private static java.lang.reflect.Method doEvaluate() {
            if (doEvaluate == null) doEvaluate = method(java.util.function.Supplier.class);
            return doEvaluate;
        }

        private static java.lang.reflect.Method doEvaluateModule() {
            if (doEvaluateModule == null) doEvaluateModule = method(ModuleSource.class, java.util.function.Function.class);
            return doEvaluateModule;
        }

        private static java.lang.reflect.Method method(Class<?>... parameters) {
            try {
                java.lang.reflect.Method m = Class.forName("org.pkl.core.EvaluatorImpl").getDeclaredMethod("doEvaluate", parameters);
                m.setAccessible(true);
                return m;
            } catch (ReflectiveOperationException | RuntimeException x) {
                throw NixException.error("builtins.pkl: 'defaults = false' needs pkl-core's EvaluatorImpl.doEvaluate, which this pkl-core lacks (" + x + ")", null);
            }
        }

        /** A member's value: an object lazily, a nix: module as its Nix value, anything else exported. */
        Object value(Object v) {
            if (v instanceof org.pkl.core.runtime.VmTyped t && t.isModuleObject()) {
                URI uri = t.getModuleInfo().getModuleKey().getUri();
                if ("nix".equals(uri.getScheme())) return lookUp(call, path(uri));
            }
            if (v instanceof org.pkl.core.runtime.VmObject o) return object(o);
            return toNix(evaluate(() -> {
                org.pkl.core.runtime.VmValue.force(v, false);
                return org.pkl.core.runtime.VmValue.export(v);
            }), call);
        }

        /** An object as the members that are set: a list for a listing, or a Dynamic of elements only. */
        Object object(org.pkl.core.runtime.VmObject o) {
            Map<Object, org.pkl.core.ast.member.ObjectMember> members = new java.util.LinkedHashMap<>();
            Set<Object> seen = new java.util.HashSet<>();
            // An object's own members are set, whoever wrote them (a function of the schema, like
            // mkForce, too); a module's, and those it gets from its parents, unless the schema did.
            boolean own = !o.isModuleObject();
            for (org.pkl.core.runtime.VmObjectLike a = o; a != null; a = a.getParent()) {
                var cursor = a.getMembers().getEntries();
                while (cursor.advance()) {
                    if (seen.add(cursor.getKey()) && set(cursor.getKey(), cursor.getValue(), own && a == o)) members.put(cursor.getKey(), cursor.getValue());
                }
            }
            boolean elements = !members.isEmpty() && members.values().stream().allMatch(org.pkl.core.ast.member.ObjectMember::isElement);
            if (o instanceof org.pkl.core.runtime.VmListing || o instanceof org.pkl.core.runtime.VmDynamic && elements) {
                List<Object> keys = new ArrayList<>(members.keySet());
                keys.sort((x, y) -> Long.compare((Long) x, (Long) y));
                Object[] items = new Object[keys.size()];
                for (int i = 0; i < items.length; i++) items[i] = nixtruffle.runtime.Apply.lazy(READ, new Member(this, o, keys.get(i)));
                return new NixList(items);
            }
            TreeMap<String, Object> out = new TreeMap<>();
            members.forEach((key, m) -> {
                String name;
                if (key instanceof String str) name = str;
                else if (m.isProp() || m.isElement()) name = key.toString();
                else throw NixException.error("Pkl: an entry with a key that isn't a string (" + key + ") can't be an attribute", null);
                out.put(Bytes.fromJava(name), nixtruffle.runtime.Apply.lazy(READ, new Member(this, o, key)));
            });
            return NixAttrs.fromMap(out);
        }

        /** Whether a member is set: not local or hidden, and (unless {@code own}) not defined by the schema or pkl:base. */
        private boolean set(Object key, org.pkl.core.ast.member.ObjectMember m, boolean own) {
            if (m.isLocalOrExternalOrHidden() || org.pkl.core.runtime.VmListing.isDefaultProperty(key)) return false;
            if (own) return true;
            var section = m.getSourceSection();
            if (section == null || !section.isAvailable()) return false;
            var source = section.getSource();
            URI uri = source.getURI();
            return source != schema && !(uri != null && "pkl".equals(uri.getScheme()));
        }
    }

    // ------------------------------------------------------------ Nix -> Pkl source

    /**
     * Writes a Nix value as a Pkl expression of the declared type {@code type}: attribute sets as
     * {@code new { name = ... }} (an instance of the property's class, with its defaults), or with
     * {@code ["key"] = ...} entries for a {@code Mapping}; lists as {@code new { ... }} elements,
     * or {@code List(...)}/{@code Set(...)}; derivations as their output path.
     */
    private static void render(Object value, PType declared, StringBuilder sb, Call call) {
        Object v = Thunk.force(value);
        PType t = unwrap(declared);
        String cls = t instanceof PType.Class c ? c.getPClass().getQualifiedName() : "";
        if (v instanceof NixNull) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Double d) {
            sb.append(d.isNaN() ? "NaN" : d.isInfinite() ? (d > 0 ? "Infinity" : "-Infinity") : d.toString());
        } else if (NixString.is(v) || v instanceof NixPath) {
            sb.append(literal(stringFor(v, call)));
        } else if (v instanceof NixAttrs a) {
            if (Derivations.isDerivation(a)) {
                sb.append(literal(stringFor(a, call)));
            } else if (cls.equals("pkl.base#Map")) {
                sb.append("Map(");
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(literal(Bytes.toJava(a.keys[i]))).append(", ");
                    render(a.forceAt(i), argument(t, 1), sb, call);
                }
                sb.append(')');
            } else {
                boolean mapping = cls.equals("pkl.base#Mapping");
                Map<String, PClass.Property> props = t instanceof PType.Class c && !mapping ? c.getPClass().getAllProperties() : Map.of();
                sb.append("new {");
                for (int i = 0; i < a.size(); i++) {
                    String name = Bytes.toJava(a.keys[i]);
                    sb.append(' ');
                    if (mapping) {
                        sb.append('[').append(literal(name)).append("] = ");
                        render(a.forceAt(i), argument(t, 1), sb, call);
                    } else {
                        PClass.Property p = props.get(name);
                        sb.append(identifier(name)).append(" = ");
                        render(a.forceAt(i), p == null ? PType.UNKNOWN : p.getType(), sb, call);
                    }
                    sb.append(';');
                }
                sb.append(" }");
            }
        } else if (v instanceof NixList l) {
            PType element = argument(t, 0);
            boolean listing = !cls.equals("pkl.base#List") && !cls.equals("pkl.base#Set");
            sb.append(listing ? "new {" : cls.equals("pkl.base#Set") ? "Set(" : "List(");
            for (int i = 0; i < l.items.length; i++) {
                if (!listing && i > 0) sb.append(", ");
                if (listing) sb.append(' ');
                render(l.items[i], element, sb, call);
                if (listing) sb.append(';');
            }
            sb.append(listing ? " }" : ")");
        } else if (v instanceof NixFunction) {
            throw NixException.error("cannot pass a function to Pkl", null);
        } else {
            throw NixException.error("cannot pass " + Values.typeName(v) + " to Pkl", null);
        }
    }

    /** The class type behind nullable, constrained and alias types (and unions of one class). */
    private static PType unwrap(PType t) {
        for (int i = 0; i < 32; i++) {
            if (t instanceof PType.Nullable n) t = n.getBaseType();
            else if (t instanceof PType.Constrained c) t = c.getBaseType();
            else if (t instanceof PType.Alias a) t = a.getAliasedType();
            else if (t instanceof PType.Union u) {
                PType only = null;
                for (PType e : u.getElementTypes()) {
                    PType x = unwrap(e);
                    if (x instanceof PType.Class) {
                        if (only != null) return PType.UNKNOWN;
                        only = x;
                    }
                }
                return only == null ? PType.UNKNOWN : only;
            } else return t;
        }
        return PType.UNKNOWN;
    }

    private static PType argument(PType t, int i) {
        List<PType> args = t instanceof PType.Class c ? c.getTypeArguments() : List.of();
        return i < args.size() ? args.get(i) : PType.UNKNOWN;
    }

    /** A string, path or derivation as a string, remembering its context. */
    private static String stringFor(Object v, Call call) {
        Set<String> context = new TreeSet<>();
        String s = Bytes.toJava(Values.coerce(v, false, true, context, null));
        if (!context.isEmpty()) call.contexts.computeIfAbsent(s, k -> new TreeSet<>()).addAll(context);
        return s;
    }

    /** A Pkl string literal. */
    private static String literal(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append("\\u{").append(Integer.toHexString(c)).append('}');
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** A Pkl identifier: any name, in backticks. */
    private static String identifier(String name) {
        return "`" + name.replace("`", "") + "`";
    }

    // ------------------------------------------------------------ nix: in Pkl

    /** The attribute path of a {@code nix:} URI: components percent-encoded, joined by '.'. */
    private static List<String> path(URI uri) {
        String ssp = uri.getRawSchemeSpecificPart();
        List<String> out = new ArrayList<>();
        for (String c : ssp.split("\\.", -1)) out.add(URLDecoder.decode(c, StandardCharsets.UTF_8));
        return out;
    }

    private static String uri(List<String> path) {
        StringBuilder sb = new StringBuilder("nix:");
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) sb.append('.');
            sb.append(URLEncoder.encode(path.get(i), StandardCharsets.UTF_8).replace(".", "%2E").replace("+", "%20"));
        }
        return sb.toString();
    }

    /** The Nix value at a path into the scope (a component {@code ~N} is a list index). */
    private static Object lookUp(Call call, List<String> path) {
        if (call.scope == null) throw NixException.error("nix: in Pkl needs builtins.pkl's 'nix' attribute set", null);
        Object v = call.scope;
        for (String c : path) {
            Object f = Thunk.force(v);
            if (c.startsWith("~") && f instanceof NixList l) {
                v = l.items[Integer.parseInt(c.substring(1))];
            } else {
                Object x = Values.attrs(f).get(Bytes.fromJava(c));
                if (x == null) throw NixException.error("nix:" + String.join(".", path) + ": attribute '" + c + "' missing", null);
                v = x;
            }
        }
        return Thunk.force(v);
    }

    /**
     * The Pkl module for a Nix value: an attribute set's attributes are its properties, each
     * {@code import("nix:...").value}, so only what Pkl uses is evaluated; anything else is the
     * module's {@code value}.
     */
    private static String moduleSource(Call call, List<String> path) {
        Object v = lookUp(call, path);
        StringBuilder sb = new StringBuilder();
        if (v instanceof NixAttrs a) {
            // A derivation's attributes are hidden: it goes back to Nix as itself, not as a copy
            // of its attributes (which include itself: drv.out.out...).
            String modifier = Derivations.isDerivation(a) ? "hidden " : "";
            sb.append("hidden value = module\n");
            for (String key : a.keys) {
                List<String> p = new ArrayList<>(path);
                p.add(Bytes.toJava(key));
                sb.append(modifier).append(identifier(Bytes.toJava(key))).append(" = import(").append(literal(uri(p))).append(").value\n");
            }
        } else if (v instanceof NixList l) {
            sb.append("value: Listing = new {");
            for (int i = 0; i < l.items.length; i++) {
                List<String> p = new ArrayList<>(path);
                p.add("~" + i);
                sb.append(" import(").append(literal(uri(p))).append(").value;");
            }
            sb.append(" }\n");
        } else if (v instanceof NixFunction) {
            sb.append("value = throw(").append(literal(uri(path) + " is a Nix function")).append(")\n");
        } else {
            sb.append("value = ");
            render(v, PType.UNKNOWN, sb, call);
            sb.append('\n');
        }
        return sb.toString();
    }

    private record NixModules(Call call) implements ModuleKeyFactory {
        @Override
        public Optional<ModuleKey> create(URI uri) {
            return uri.getScheme() != null && uri.getScheme().equals("nix") ? Optional.of(new NixModuleKey(call, uri)) : Optional.empty();
        }
    }

    private record NixModuleKey(Call call, URI uri) implements ModuleKey {
        @Override
        public URI getUri() {
            return uri;
        }

        @Override
        public boolean hasHierarchicalUris() {
            return false;
        }

        @Override
        public boolean isGlobbable() {
            return false;
        }

        @Override
        public ResolvedModuleKey resolve(SecurityManager securityManager) throws IOException {
            try {
                String source = call.inNix(() -> moduleSource(call, path(uri)));
                return ResolvedModuleKeys.virtual(this, uri, source, true);
            } catch (NixException e) {
                throw new IOException(Bytes.toJava(e.getMessage()));
            }
        }
    }

    /** {@code read("nix:a.b")}: a Nix value as text, JSON for attribute sets and lists. */
    private record NixResources(Call call) implements ResourceReader {
        @Override
        public String getUriScheme() {
            return "nix";
        }

        @Override
        public boolean hasHierarchicalUris() {
            return false;
        }

        @Override
        public boolean isGlobbable() {
            return false;
        }

        @Override
        public Optional<Object> read(URI uri) throws IOException {
            try {
                return Optional.of(call.inNix(() -> {
                    Object v = lookUp(call, path(uri));
                    if (v instanceof NixAttrs a && !Derivations.isDerivation(a) || v instanceof NixList) {
                        return Bytes.toJava(Json.toJSON(v, new LinkedHashSet<>(), false, false));
                    }
                    if (v instanceof Boolean || v instanceof Long || v instanceof Double || v instanceof NixNull) return String.valueOf(v instanceof NixNull ? "null" : v);
                    return stringFor(v, call);
                }));
            } catch (NixException e) {
                throw new IOException(Bytes.toJava(e.getMessage()));
            }
        }
    }
}
