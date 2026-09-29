package nixtruffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import nixtruffle.nodes.ApplyThunkRootNode;
import nixtruffle.parser.Parser;

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

    private RootCallTarget applyThunkTarget;

    public static NixLanguage get(Node node) {
        return REFERENCE.get(node);
    }

    @Override
    protected NixContext createContext(Env env) {
        return new NixContext(this, env);
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        return parseSource(request.getSource());
    }

    /** Parses a Nix file; calling the result (with no arguments) evaluates it to weak head normal form. */
    public RootCallTarget parseSource(Source source) {
        return new Translator(this, source).translateFile(new Parser(source).parseFile()).getCallTarget();
    }

    /**
     * Parses a REPL input. Free variables may refer to the REPL scope: an attrset passed as the
     * single call argument (they shadow builtins, like in {@code nix repl}).
     */
    public RootCallTarget parseRepl(Source source, java.util.Set<String> scopeNames) {
        return new Translator(this, source, scopeNames).translateFile(new Parser(source).parseFile()).getCallTarget();
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
