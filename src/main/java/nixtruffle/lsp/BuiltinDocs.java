package nixtruffle.lsp;

import nixtruffle.runtime.Bytes;
import nixtruffle.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * The builtins' documentation (CppNix's, LGPL-2.1-or-later: see bin/builtin-docs, which makes
 * the resource), as markdown for hover and completion.
 */
final class BuiltinDocs {
    private BuiltinDocs() {}

    private static volatile Map<String, Object> docs;

    private static Map<String, Object> docs() {
        if (docs == null) {
            try (InputStream in = BuiltinDocs.class.getResourceAsStream("/nixtruffle/lsp/builtins.json")) {
                docs = in == null ? Map.of() : Json.obj(Json.obj(Json.parse(Bytes.of(in.readAllBytes()))).get("builtins"));
            } catch (IOException | RuntimeException e) {
                docs = Map.of();
            }
        }
        return docs;
    }

    /** The signature of a builtin ({@code map f list}), or null; {@code __add} is {@code add}. */
    static String signature(String name) {
        Map<String, Object> d = entry(name);
        if (d == null) return null;
        StringBuilder sb = new StringBuilder(name);
        for (Object a : (List<?>) d.get("args")) sb.append(' ').append(Bytes.toJava((String) a));
        return sb.toString();
    }

    /** A builtin's documentation (markdown), or null. */
    static String doc(String name) {
        Map<String, Object> d = entry(name);
        return d == null ? null : Bytes.toJava((String) d.get("doc"));
    }

    private static Map<String, Object> entry(String name) {
        Object d = docs().get(Bytes.fromJava(name.startsWith("__") ? name.substring(2) : name));
        return d instanceof Map<?, ?> ? Json.obj(d) : null;
    }
}
