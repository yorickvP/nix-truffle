package nixtruffle.builtins;

import nixtruffle.runtime.Derivations;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixLambda;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.NixNull;
import nixtruffle.runtime.NixPath;
import nixtruffle.runtime.NixString;
import nixtruffle.runtime.Thunk;
import nixtruffle.runtime.Values;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** {@code builtins.toXML}: a port of CppNix's printValueAsXML (strict, no locations) and XMLWriter. */
final class Xml {
    private final StringBuilder out = new StringBuilder("<?xml version='1.0' encoding='utf-8'?>\n");
    private final Set<String> context;
    private final Set<String> drvsSeen = new HashSet<>();
    private int depth;

    private Xml(Set<String> context) {
        this.context = context;
    }

    static Object toXML(Object value) {
        Set<String> context = new TreeSet<>();
        Xml x = new Xml(context);
        x.open("expr", new TreeMap<>());
        x.value(value);
        x.close("expr");
        return NixString.make(x.out.toString(), context);
    }

    private static TreeMap<String, String> attrs(String... kv) {
        TreeMap<String, String> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private void writeAttrs(TreeMap<String, String> attrs) {
        for (var e : attrs.entrySet()) {
            out.append(' ').append(e.getKey()).append("=\"");
            String v = e.getValue();
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                switch (c) {
                    case '"' -> out.append("&quot;");
                    case '<' -> out.append("&lt;");
                    case '>' -> out.append("&gt;");
                    case '&' -> out.append("&amp;");
                    case '\n' -> out.append("&#xA;");
                    default -> out.append(c);
                }
            }
            out.append('"');
        }
    }

    private void open(String name, TreeMap<String, String> attrs) {
        out.append("  ".repeat(depth)).append('<').append(name);
        writeAttrs(attrs);
        out.append(">\n");
        depth++;
    }

    private void close(String name) {
        depth--;
        out.append("  ".repeat(depth)).append("</").append(name).append(">\n");
    }

    private void empty(String name, TreeMap<String, String> attrs) {
        out.append("  ".repeat(depth)).append('<').append(name);
        writeAttrs(attrs);
        out.append(" />\n");
    }

    /** Every value is a level of {@code max-call-depth}, forced inside it (like CppNix). */
    private void value(Object raw) {
        nixtruffle.NixContext ctx = nixtruffle.runtime.CallDepth.enter(null);
        try {
            valueForced(Thunk.force(raw));
        } finally {
            nixtruffle.runtime.CallDepth.exit(ctx);
        }
    }

    private void valueForced(Object v) {
        switch (v) {
            case Long l -> empty("int", attrs("value", Long.toString(l)));
            case Boolean b -> empty("bool", attrs("value", b.toString()));
            case String s -> empty("string", attrs("value", s));
            case NixString s -> {
                context.addAll(Arrays.asList(s.context));
                empty("string", attrs("value", s.value));
            }
            case NixPath p -> empty("path", attrs("value", p.path));
            case NixNull n -> empty("null", attrs());
            case Double d -> empty("float", attrs("value", Values.formatFloat(d)));
            case NixAttrs a when Derivations.isDerivation(a) -> {
                TreeMap<String, String> xmlAttrs = new TreeMap<>();
                String drvPath = "";
                Object dp = a.get("drvPath");
                if (NixString.is(dp)) xmlAttrs.put("drvPath", drvPath = NixString.value(dp));
                Object op = a.get("outPath");
                if (NixString.is(op)) xmlAttrs.put("outPath", NixString.value(op));
                open("derivation", xmlAttrs);
                if (!drvPath.isEmpty() && drvsSeen.add(drvPath)) {
                    showAttrs(a);
                } else {
                    empty("repeated", attrs());
                }
                close("derivation");
            }
            case NixAttrs a -> {
                open("attrs", attrs());
                showAttrs(a);
                close("attrs");
            }
            case NixList l -> {
                open("list", attrs());
                for (int i = 0; i < l.size(); i++) value(l.items[i]);
                close("list");
            }
            case NixLambda f -> {
                open("function", attrs());
                if (f.info.hasFormals()) {
                    TreeMap<String, String> pat = new TreeMap<>();
                    if (f.info.argName() != null) pat.put("name", f.info.argName());
                    if (f.info.ellipsis()) pat.put("ellipsis", "1");
                    open("attrspat", pat);
                    String[] names = f.info.formals().clone();
                    Arrays.sort(names);
                    for (String n : names) empty("attr", attrs("name", n));
                    close("attrspat");
                } else {
                    empty("varpat", attrs("name", f.info.argName()));
                }
                close("function");
            }
            default -> empty("unevaluated", attrs());
        }
    }

    private void showAttrs(NixAttrs a) {
        for (int i = 0; i < a.size(); i++) {
            open("attr", attrs("name", a.keys[i]));
            value(a.values[i]);
            close("attr");
        }
    }
}
