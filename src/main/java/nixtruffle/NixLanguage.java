package nixtruffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Option;
import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;
import org.graalvm.options.OptionValues;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import nixtruffle.builtins.Builtins;
import nixtruffle.nodes.ApplyThunkRootNode;
import nixtruffle.parser.Parser;
import nixtruffle.runtime.Bytes;
import nixtruffle.runtime.NixNull;
import nixtruffle.util.Proc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@TruffleLanguage.Registration(
        id = NixLanguage.ID,
        name = "Nix",
        version = "0.1",
        defaultMimeType = NixLanguage.MIME,
        characterMimeTypes = NixLanguage.MIME,
        contextPolicy = TruffleLanguage.ContextPolicy.SHARED,
        fileTypeDetectors = NixFileDetector.class)
public final class NixLanguage extends TruffleLanguage<NixContext> {
    public static final String ID = "nix";
    public static final String MIME = "application/x-nix";

    private static final LanguageReference<NixLanguage> REFERENCE = LanguageReference.create(NixLanguage.class);

    @Option(help = "Nix settings, like lines of nix.conf (applied after the configuration files).",
            category = OptionCategory.USER, stability = OptionStability.STABLE)
    static final OptionKey<String> Config = new OptionKey<>("");

    @Option(help = "Lookup path entries (like -I), one per line.", category = OptionCategory.USER, stability = OptionStability.STABLE)
    static final OptionKey<String> IncludePath = new OptionKey<>("");

    @Option(help = "Don't write to the Nix store (like Nix's read-only mode).", category = OptionCategory.USER, stability = OptionStability.STABLE)
    static final OptionKey<Boolean> ReadOnly = new OptionKey<>(false);

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return new NixLanguageOptionDescriptors();
    }

    private RootCallTarget applyThunkTarget;

    /**
     * The language, and so parsed code, is shared by all contexts of an engine, whatever their
     * settings: code depends on a context only through its {@link GlobalScope}.
     */
    @Override
    protected boolean areOptionsCompatible(OptionValues firstOptions, OptionValues newOptions) {
        return true;
    }

    private final ConcurrentHashMap<List<String>, GlobalScope> scopes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<List<String>, String[]> keyArrays = new ConcurrentHashMap<>();

    /** The scope of a base environment: the same object for the same names. */
    GlobalScope globalScope(Map<String, Object> globals) {
        String[] names = globals.keySet().toArray(new String[0]);
        return scopes.computeIfAbsent(List.of(names), k -> {
            Object[] constants = new Object[names.length];
            for (int i = 0; i < names.length; i++) {
                Object v = globals.get(names[i]);
                if (v instanceof Boolean || v == NixNull.INSTANCE || Builtins.isPrimOp(v)) constants[i] = v;
            }
            return new GlobalScope(names, constants);
        });
    }

    /**
     * An array equal to {@code keys}, the same one for every context: attribute selection caches
     * on the identity of the keys, so {@code builtins.x} stays monomorphic across contexts.
     */
    public String[] internKeys(String[] keys) {
        return keyArrays.computeIfAbsent(List.of(keys), k -> keys);
    }

    private record Parsed(String text, GlobalScope scope, RootCallTarget target) {}

    /**
     * Parsed files, by path: shared between contexts, so an engine that runs several contexts
     * (the daemon) parses and compiles a file once, until its contents change.
     */
    private final Map<String, Parsed> parsed = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Parsed> eldest) {
            return size() > 50_000;
        }
    });

    public static NixLanguage get(Node node) {
        return REFERENCE.get(node);
    }

    private final com.oracle.truffle.api.ContextThreadLocal<nixtruffle.runtime.EvalThread> evalThreads =
            locals.createContextThreadLocal((ctx, thread) -> new nixtruffle.runtime.EvalThread(ctx.maxCallDepth));

    /** The current thread's state in the current context. */
    public nixtruffle.runtime.EvalThread evalThread() {
        return evalThreads.get();
    }

    @Override
    protected NixContext createContext(Env env) {
        Settings settings = Settings.load();
        settings.parse(env.getOptions().get(Config), null);
        for (String entry : env.getOptions().get(IncludePath).split("\n")) if (!entry.isEmpty()) settings.includePath.add(entry);
        return new NixContext(this, env, settings, env.getOptions().get(ReadOnly));
    }

    /**
     * Sources from other languages and the polyglot API are Java text: they are converted to a
     * byte string (UTF-8) first. Relative paths resolve against the source's directory, or the
     * current directory.
     */
    @Override
    protected CallTarget parse(ParsingRequest request) {
        Source source = request.getSource();
        String path = source.getPath() == null ? null : Bytes.fromJava(source.getPath());
        return new ParseRootNode(this, Bytes.fromJava(source.getCharacters().toString()), Bytes.fromJava(source.getName()), path).getCallTarget();
    }

    /**
     * What {@link #parse(ParsingRequest)} returns: Truffle reuses it for every context, but the
     * code depends on the context's {@link GlobalScope}, so it parses when called.
     */
    private static final class ParseRootNode extends RootNode {
        private final String text, name, path;
        /** The code for the last context's scope and (for a source without a path) directory. */
        private volatile Code last;
        @Child private IndirectCallNode call = IndirectCallNode.create();

        private record Code(GlobalScope scope, String dir, RootCallTarget target) {}

        ParseRootNode(NixLanguage language, String text, String name, String path) {
            super(language);
            this.text = text;
            this.name = name;
            this.path = path;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return call.call(target());
        }

        @TruffleBoundary
        private RootCallTarget target() {
            GlobalScope scope = NixContext.get(null).globalScope();
            // Without a path, relative paths resolve against the current directory.
            String dir = path == null ? cwd() : null;
            Code c = last;
            if (c == null || c.scope != scope || !Objects.equals(c.dir, dir)) {
                c = new Code(scope, dir, NixLanguage.get(null).parse(text, name, path, dir, null));
                last = c;
            }
            return c.target;
        }
    }

    /**
     * Parses Nix source, a byte string (see {@link Bytes}); calling the result (with no arguments)
     * evaluates it to weak head normal form. {@code path} is the file it was read from (for
     * relative paths and positions), or null; then relative paths resolve against {@code baseDir},
     * or the current directory. With {@code replNames}, free variables may refer to the REPL
     * scope: an attrset passed as the single call argument (they shadow builtins, like in {@code
     * nix repl}).
     */
    public RootCallTarget parse(String text, String name, String path, String baseDir, Set<String> replNames) {
        GlobalScope scope = NixContext.get(null).globalScope();
        boolean cache = path != null && path.equals(name) && baseDir == null && replNames == null;
        if (cache) {
            Parsed p = parsed.get(path);
            if (p != null && p.scope == scope && p.text.equals(text)) return p.target;
        }
        Source source = Source.newBuilder(ID, text, name).build();
        if (baseDir == null) baseDir = path != null && path.contains("/") ? path.substring(0, Math.max(1, path.lastIndexOf('/'))) : cwd();
        RootCallTarget target = new Translator(this, source, path, baseDir, replNames, scope).translateFile(new Parser(source).parseFile()).getCallTarget();
        if (cache) parsed.put(path, new Parsed(text, scope, target));
        return target;
    }

    /** The current directory, as a byte string. */
    public static String cwd() {
        return Bytes.fromJava(Proc.cwd());
    }

    public RootCallTarget applyThunkTarget() {
        if (applyThunkTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            applyThunkTarget = new ApplyThunkRootNode(this).getCallTarget();
        }
        return applyThunkTarget;
    }

    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        // Thunks are claimed and published safely once there are workers (see Thunk, Parallel).
        return true;
    }

    @Override
    protected void finalizeContext(NixContext context) {
        if (context.parallel != null) context.parallel.stop();
    }
}
