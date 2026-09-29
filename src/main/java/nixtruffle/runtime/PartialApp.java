package nixtruffle.runtime;

/** A builtin applied to fewer arguments than its arity. */
public final class PartialApp extends NixFunction {
    public final Builtin fn;
    public final Object[] args;

    public PartialApp(Builtin fn, Object[] args) {
        this.fn = fn;
        this.args = args;
    }
}
