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
    public final nixtruffle.store.Store store = new nixtruffle.store.Store();
    private Object derivationLambda;

    NixContext(NixLanguage language, Env env) {
        this.language = language;
        this.env = env;
        this.err = new PrintStream(env.err(), true);
    }

    public static NixContext get(Node node) {
        return REFERENCE.get(node);
    }

    /** {@code "${./foo}"}: the store path the file tree at {@code path} would be copied to. */
    public String copyPathToStore(String path, Node location) {
        if (path.endsWith(".drv")) throw nixtruffle.runtime.NixException.error("file names are not allowed to end in '.drv'", location);
        com.oracle.truffle.api.TruffleFile file = env.getPublicTruffleFile(path);
        if (!file.exists(java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw nixtruffle.runtime.NixException.error("getting status of '" + path + "': No such file or directory", location);
        }
        String nameError = nixtruffle.store.StorePaths.checkName(file.getName());
        if (nameError != null) throw nixtruffle.runtime.NixException.error(nameError, location);
        try {
            return store.copyPathToStore(file);
        } catch (java.io.IOException e) {
            throw nixtruffle.runtime.NixException.error("cannot copy '" + path + "' to the store: " + e.getMessage(), location);
        }
    }

    /** Nix source of a corepkgs file ({@code <nix/fetchurl.nix>}, {@code derivation}), or null. */
    public static String corepkg(String name) {
        try (var in = NixContext.class.getResourceAsStream("/nixtruffle/corepkgs/" + name)) {
            return in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** The {@code derivation} function: Lix's derivation.nix wrapper around derivationStrict. */
    public Object derivationLambda() {
        if (derivationLambda == null) {
            var source = com.oracle.truffle.api.source.Source.newBuilder(NixLanguage.ID, corepkg("derivation.nix"), "/__corepkgs__/derivation.nix").build();
            derivationLambda = language.parseSource(source).call();
        }
        return derivationLambda;
    }
}
