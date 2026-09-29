package nixtruffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Option;
import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import nixtruffle.nodes.ApplyThunkRootNode;
import nixtruffle.parser.Parser;
import nixtruffle.runtime.Bytes;

@TruffleLanguage.Registration(
        id = NixLanguage.ID,
        name = "Nix",
        version = "0.1",
        defaultMimeType = NixLanguage.MIME,
        characterMimeTypes = NixLanguage.MIME,
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

    public static NixLanguage get(Node node) {
        return REFERENCE.get(node);
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
        return parse(Bytes.fromJava(source.getCharacters().toString()), Bytes.fromJava(source.getName()), path, null, null);
    }

    /**
     * Parses Nix source, a byte string (see {@link Bytes}); calling the result (with no arguments)
     * evaluates it to weak head normal form. {@code path} is the file it was read from (for
     * relative paths and positions), or null; then relative paths resolve against {@code baseDir},
     * or the current directory. With {@code replNames}, free variables may refer to the REPL
     * scope: an attrset passed as the single call argument (they shadow builtins, like in {@code
     * nix repl}).
     */
    public RootCallTarget parse(String text, String name, String path, String baseDir, java.util.Set<String> replNames) {
        Source source = Source.newBuilder(ID, text, name).build();
        if (baseDir == null) baseDir = path != null && path.contains("/") ? path.substring(0, Math.max(1, path.lastIndexOf('/'))) : cwd();
        return new Translator(this, source, path, baseDir, replNames).translateFile(new Parser(source).parseFile()).getCallTarget();
    }

    /** The current directory, as a byte string. */
    public static String cwd() {
        return Bytes.fromJava(System.getProperty("user.dir"));
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
        // Thunks are updated in place without synchronization.
        return singleThreaded;
    }
}
