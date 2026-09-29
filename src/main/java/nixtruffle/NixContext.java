package nixtruffle;

import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.Node;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

public final class NixContext {
    private static final ContextReference<NixContext> REFERENCE = ContextReference.create(NixLanguage.class);

    public final NixLanguage language;
    public final Env env;
    /** Results of {@code import}, per canonical path (Nix evaluates each file once). */
    public final Map<String, Object> importCache = new HashMap<>();
    public final PrintStream err;

    NixContext(NixLanguage language, Env env) {
        this.language = language;
        this.env = env;
        this.err = new PrintStream(env.err(), true);
    }

    public static NixContext get(Node node) {
        return REFERENCE.get(node);
    }
}
