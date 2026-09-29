package nixtruffle.nodes;

import com.oracle.truffle.api.dsl.ImplicitCast;
import com.oracle.truffle.api.dsl.TypeSystem;

/** Unboxed specializations for ints, floats and booleans; ints widen to floats in mixed arithmetic. */
@TypeSystem({long.class, double.class, boolean.class})
public abstract class NixTypes {
    @ImplicitCast
    public static double castDouble(long value) {
        return value;
    }
}
