package nixtruffle;

import java.util.HashMap;
import java.util.Map;

/**
 * The names of a context's base environment ({@code builtins}, {@code map}, {@code __add}, ...),
 * which the translator resolves free variables against. The language is shared between contexts
 * (and so is parsed code), and contexts with the same names share a scope: parsed code reads a
 * global from the running context by its index here, unless it is the same value in every context
 * (a primop, {@code true}, {@code false} or {@code null}), which the code holds as a constant.
 */
public final class GlobalScope {
    final String[] names;
    private final Map<String, Integer> index = new HashMap<>();
    /** Per name, the value if it is the same in every context, else null. */
    private final Object[] constants;

    GlobalScope(String[] names, Object[] constants) {
        this.names = names;
        this.constants = constants;
        for (int i = 0; i < names.length; i++) index.put(names[i], i);
    }

    public int size() {
        return names.length;
    }

    /** The index of a global, or -1. */
    public int indexOf(String name) {
        Integer i = index.get(name);
        return i == null ? -1 : i;
    }

    public String name(int i) {
        return names[i];
    }

    /** The value of global {@code i} if it is the same in every context, else null. */
    public Object constant(int i) {
        return constants[i];
    }
}
