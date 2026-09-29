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

    public Builtin(String name, int arity, Impl impl) {
        this.name = name;
        this.arity = arity;
        this.impl = impl;
    }

    @Override
    public String toString() { return Bytes.fromJava("«primop ") + name + Bytes.fromJava("»"); }
}
