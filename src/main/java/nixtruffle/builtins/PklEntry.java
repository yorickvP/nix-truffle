package nixtruffle.builtins;

import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;

/**
 * Calls into {@link Pkl}, whose jars native images leave out (bin/build-native): without them,
 * it can't be linked, and using Pkl is an error.
 */
final class PklEntry {
    private PklEntry() {}

    static Object importFile(String file) {
        try {
            return Pkl.importFile(file);
        } catch (LinkageError e) {
            throw missing();
        }
    }

    static Object eval(NixAttrs args) {
        try {
            return Pkl.eval(args);
        } catch (LinkageError e) {
            throw missing();
        }
    }

    private static NixException missing() {
        return NixException.error("Pkl is not in native images: use nix-truffle on the JVM (the flake's default package)", null);
    }
}
