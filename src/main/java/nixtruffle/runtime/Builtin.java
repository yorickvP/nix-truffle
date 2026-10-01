package nixtruffle.runtime;

/**
 * A primop implemented in Java. {@code impl} receives {@code arity} lazy arguments (possibly
 * thunks) and must return a value in weak head normal form.
 */
public final class Builtin extends NixFunction {
    public interface Impl {
        Object apply(Object[] args);
    }

    public final String name;
    public final int arity;
    public final Impl impl;
    /** Has side effects (output, fetching, other languages), which only the main thread has (see {@link Parallel}). */
    public final boolean mainOnly;

    private static final java.util.Set<String> MAIN_ONLY = java.util.Set.of("break", "wasm", "polyglotEval", "polyglotExport", "polyglotImport");

    public Builtin(String name, int arity, Impl impl) {
        this.name = name;
        this.arity = arity;
        this.impl = impl;
        this.mainOnly = MAIN_ONLY.contains(name);
    }

    @Override
    public String toString() { return Bytes.fromJava("«primop ") + name + Bytes.fromJava("»"); }
}
