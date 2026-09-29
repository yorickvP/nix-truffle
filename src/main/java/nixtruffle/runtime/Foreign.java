package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;

/**
 * The boundary between Nix and other Truffle languages.
 *
 * <p>Values coming in are normalised with {@link #toNix}: foreign numbers, strings, booleans and
 * null become their Nix counterparts; other foreign objects stay opaque and can be selected from
 * ({@code obj.member}), called ({@code f a b}, all arguments in one foreign call), or used as
 * lists when they have array elements. Nix values going out are already interop objects: attrsets
 * have members, lists have array elements, and functions are executable (curried).
 */
public final class Foreign {
    private Foreign() {}

    public static boolean isForeign(Object v) {
        return v instanceof TruffleObject && !(v instanceof NixObject);
    }

    /**
     * A Nix value as other languages see it: strings, which are byte strings in Nix (see {@link
     * Bytes}), become Java text.
     */
    @TruffleBoundary
    public static Object out(Object v) {
        return v instanceof String s && !Bytes.isAscii(s) ? new ByteString(s) : v;
    }

    @TruffleBoundary
    public static Object toNix(Object v) {
        if (v instanceof String s) return Bytes.fromJava(s);
        if (v instanceof ByteString b) return b.value;
        if (v instanceof Long || v instanceof Double || v instanceof Boolean || v instanceof NixObject) return v;
        if (v instanceof Integer i) return (long) i;
        if (v instanceof Short s) return (long) s;
        if (v instanceof Byte b) return (long) b;
        if (v instanceof Float f) return (double) f;
        if (v instanceof Character c) return Bytes.fromJava(String.valueOf(c));
        if (v == null) return NixNull.INSTANCE;
        InteropLibrary lib = InteropLibrary.getUncached(v);
        try {
            if (lib.isNull(v)) return NixNull.INSTANCE;
            if (lib.isBoolean(v)) return lib.asBoolean(v);
            if (lib.isString(v)) return Bytes.fromJava(lib.asString(v));
            if (lib.isNumber(v)) return lib.fitsInLong(v) ? (Object) lib.asLong(v) : (Object) lib.asDouble(v);
        } catch (InteropException e) {
            throw NixException.error("foreign value conversion failed: " + e.getMessage(), null);
        }
        return v;
    }

    /** {@code obj.key} on a foreign object: members first, then hash entries (e.g. Python dicts). Null if absent. */
    @TruffleBoundary
    public static Object select(Object obj, String key) {
        InteropLibrary lib = InteropLibrary.getUncached(obj);
        try {
            String member = Bytes.toJava(key);
            if (lib.isMemberReadable(obj, member)) return toNix(lib.readMember(obj, member));
            if (lib.hasHashEntries(obj) && lib.isHashEntryReadable(obj, member)) return toNix(lib.readHashValue(obj, member));
        } catch (InteropException e) {
            throw NixException.error("cannot read foreign member '" + key + "': " + e.getMessage(), null);
        }
        return null;
    }

    @TruffleBoundary
    public static boolean hasMembers(Object obj) {
        InteropLibrary lib = InteropLibrary.getUncached(obj);
        return lib.hasMembers(obj) || lib.hasHashEntries(obj);
    }

    @TruffleBoundary
    public static Object call(Object fn, Object[] lazyArgs, Node location) {
        InteropLibrary lib = InteropLibrary.getUncached(fn);
        if (!lib.isExecutable(fn)) {
            throw NixException.error("attempt to call something which is not a function but " + Values.typeName(fn), location);
        }
        Object[] args = new Object[lazyArgs.length];
        for (int i = 0; i < args.length; i++) args[i] = out(Thunk.force(lazyArgs[i]));
        try {
            return toNix(lib.execute(fn, args));
        } catch (InteropException e) {
            throw NixException.error("foreign call failed: " + e.getMessage(), location);
        }
    }

    /** Elements of a foreign array (converted), or null if the value has no array elements. */
    @TruffleBoundary
    public static Object[] asArray(Object obj) {
        InteropLibrary lib = InteropLibrary.getUncached(obj);
        if (!lib.hasArrayElements(obj)) return null;
        try {
            long size = lib.getArraySize(obj);
            Object[] out = new Object[(int) size];
            for (int i = 0; i < out.length; i++) out[i] = toNix(lib.readArrayElement(obj, i));
            return out;
        } catch (InteropException e) {
            throw NixException.error("cannot read foreign array: " + e.getMessage(), null);
        }
    }

    /** A foreign object's members as a Nix attrset (for printing and {@code builtins.attrNames}). */
    @TruffleBoundary
    public static NixAttrs asAttrs(Object obj) {
        InteropLibrary lib = InteropLibrary.getUncached(obj);
        java.util.TreeMap<String, Object> map = new java.util.TreeMap<>();
        try {
            if (lib.hasMembers(obj)) {
                Object members = lib.getMembers(obj);
                InteropLibrary ml = InteropLibrary.getUncached(members);
                long n = ml.getArraySize(members);
                for (long i = 0; i < n; i++) {
                    String name = InteropLibrary.getUncached().asString(ml.readArrayElement(members, i));
                    if (lib.isMemberReadable(obj, name)) map.put(Bytes.fromJava(name), toNix(lib.readMember(obj, name)));
                }
            } else if (lib.hasHashEntries(obj)) {
                Object it = lib.getHashKeysIterator(obj);
                InteropLibrary il = InteropLibrary.getUncached(it);
                while (il.hasIteratorNextElement(it)) {
                    Object key = il.getIteratorNextElement(it);
                    map.put(Values.string(toNix(key)), toNix(lib.readHashValue(obj, key)));
                }
            }
        } catch (InteropException e) {
            throw NixException.error("cannot read foreign members: " + e.getMessage(), null);
        }
        return NixAttrs.fromMap(map);
    }

    @TruffleBoundary
    public static boolean isExecutable(Object obj) {
        return InteropLibrary.getUncached(obj).isExecutable(obj);
    }

    @TruffleBoundary
    public static String display(Object obj) {
        InteropLibrary lib = InteropLibrary.getUncached(obj);
        try {
            String lang = lib.hasLanguage(obj) ? lib.getLanguage(obj).getSimpleName().replace("Language", "").toLowerCase() : "host";
            return Bytes.fromJava("«" + lang + " " + InteropLibrary.getUncached().asString(lib.toDisplayString(obj, false)) + "»");
        } catch (InteropException e) {
            return Bytes.fromJava("«foreign»");
        }
    }
}
