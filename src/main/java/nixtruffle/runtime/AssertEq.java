package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * CppNix's {@code assertEqValues}: when {@code assert a == b} fails, finds where the values
 * differ and throws an assertion error that says so. Returns normally if it finds no difference.
 */
public final class AssertEq {
    private AssertEq() {}

    private static NixException fail(String message) {
        return new NixException.Catchable(message, null);
    }

    private static String show(Object v) {
        return ValuePrinter.printForError(v);
    }

    @TruffleBoundary
    public static void check(Object a, Object b) {
        nixtruffle.NixContext ctx = CallDepth.enter(null);
        try {
            checkForced(a, b);
        } finally {
            CallDepth.exit(ctx);
        }
    }

    private static void checkForced(Object a, Object b) {
        Object v1 = Thunk.force(a);
        Object v2 = Thunk.force(b);
        if (v1 == v2) return;
        boolean n1 = v1 instanceof Long || v1 instanceof Double, n2 = v2 instanceof Long || v2 instanceof Double;
        if (n1 && n2) {
            if (Values.equal(v1, v2)) return;
            throw fail(Values.typeName(v1) + " with value '" + show(v1) + "' is not equal to " + Values.typeName(v2) + " with value '" + show(v2) + "'");
        }
        if (!Values.typeOf(v1).equals(Values.typeOf(v2))) {
            throw fail(Values.typeName(v1) + " of value '" + show(v1) + "' is not equal to " + Values.typeName(v2) + " of value '" + show(v2) + "'");
        }
        switch (v1) {
            case Long i -> {
                if (!i.equals(v2)) throw fail("integer '" + i + "' is not equal to integer '" + v2 + "'");
            }
            case Boolean x -> {
                if (!x.equals(v2)) throw fail("boolean '" + x + "' is not equal to boolean '" + v2 + "'");
            }
            case NixPath p -> {
                if (!p.path.equals(((NixPath) v2).path)) throw fail("path '" + show(v1) + "' is not equal to path '" + show(v2) + "'");
            }
            case NixNull n -> {}
            case NixList l1 -> {
                NixList l2 = (NixList) v2;
                if (l1.size() != l2.size()) {
                    throw fail("list of size '" + l1.size() + "' is not equal to list of size '" + l2.size() + "', left hand side is '" + show(l1)
                            + "', right hand side is '" + show(l2) + "'");
                }
                for (int i = 0; i < l1.size(); i++) check(l1.items[i], l2.items[i]);
            }
            case NixAttrs s1 -> attrs(s1, (NixAttrs) v2);
            case Double d -> {
                if (d != (double) (Double) v2) throw fail("float '" + String.format("%f", d) + "' is not equal to float '" + String.format("%f", (Double) v2) + "'");
            }
            default -> {
                if (NixString.is(v1)) {
                    if (!NixString.value(v1).equals(NixString.value(v2))) throw fail("string '" + show(v1) + "' is not equal to string '" + show(v2) + "'");
                } else if (v1 instanceof NixFunction) {
                    throw fail("distinct functions and immediate comparisons of identical functions compare as unequal");
                }
            }
        }
    }

    private static void attrs(NixAttrs s1, NixAttrs s2) {
        if (Derivations.isDerivation(s1) && Derivations.isDerivation(s2)) {
            Object o1 = s1.getRaw("outPath"), o2 = s2.getRaw("outPath");
            if (o1 != null && o2 != null) {
                check(o1, o2);
                return;
            }
        }
        if (s1.size() != s2.size()) {
            throw fail("attribute names of attribute set '" + show(s1) + "' differs from attribute set '" + show(s2) + "'");
        }
        for (int i = 0; i < s1.size(); i++) {
            if (!s1.keys[i].equals(s2.keys[i])) {
                if (s2.indexOf(s1.keys[i]) < 0) {
                    throw fail("attribute name '" + s1.keys[i] + "' is contained in '" + show(s1) + "', but not in '" + show(s2) + "'");
                }
                throw fail("attribute name '" + s2.keys[i] + "' is missing in '" + show(s1) + "', but is contained in '" + show(s2) + "'");
            }
            check(s1.values[i], s2.values[i]);
        }
    }
}
