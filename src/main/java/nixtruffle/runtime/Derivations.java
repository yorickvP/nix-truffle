package nixtruffle.runtime;

/** Derivation helpers. */
public final class Derivations {
    private Derivations() {}

    public static boolean isDerivation(NixAttrs a) {
        Object type = a.getRaw("type");
        if (type == null) return false;
        Object t = Thunk.force(type);
        return "derivation".equals(t instanceof String s ? s : null);
    }
}
