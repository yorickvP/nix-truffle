package nixtruffle.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** A store derivation, serialized in the ATerm format of {@code .drv} files. */
public final class Derivation {
    /** {@code path} is "" while deferred; {@code hashAlgo}/{@code hash} are set for fixed outputs. */
    public record Output(String path, String hashAlgo, String hash) {
        public static final Output DEFERRED = new Output("", "", "");
    }

    public final String name;
    public final TreeMap<String, Output> outputs = new TreeMap<>();
    public final TreeMap<String, TreeSet<String>> inputDrvs = new TreeMap<>();
    public final TreeSet<String> inputSrcs = new TreeSet<>();
    public String platform = "";
    public String builder = "";
    public final List<String> args = new ArrayList<>();
    public final TreeMap<String, String> env = new TreeMap<>();

    public Derivation(String name) {
        this.name = name;
    }

    public boolean isFixedOutput() {
        return outputs.size() == 1 && outputs.containsKey("out") && !outputs.get("out").hashAlgo().isEmpty();
    }

    /** References of the .drv file: its sources and input derivations. */
    public TreeSet<String> references() {
        TreeSet<String> refs = new TreeSet<>(inputSrcs);
        refs.addAll(inputDrvs.keySet());
        return refs;
    }

    /**
     * Port of libstore's {@code Derivation::unparse}. With {@code maskOutputs}, output paths and the
     * corresponding environment variables are blanked; {@code actualInputs} replaces the input
     * derivation paths (with their hashes modulo, in {@code hashDerivationModulo}).
     */
    public String unparse(boolean maskOutputs, Map<String, TreeSet<String>> actualInputs) {
        StringBuilder s = new StringBuilder(4096);
        s.append("Derive([");
        boolean first = true;
        for (Map.Entry<String, Output> o : outputs.entrySet()) {
            if (!first) s.append(',');
            first = false;
            s.append('(');
            quoteRaw(s, o.getKey());
            s.append(',');
            quoteRaw(s, maskOutputs ? "" : o.getValue().path());
            s.append(',');
            quoteRaw(s, o.getValue().hashAlgo());
            s.append(',');
            quoteRaw(s, o.getValue().hash());
            s.append(')');
        }
        s.append("],[");
        first = true;
        for (Map.Entry<String, TreeSet<String>> i : (actualInputs != null ? actualInputs : inputDrvs).entrySet()) {
            if (!first) s.append(',');
            first = false;
            s.append('(');
            quoteRaw(s, i.getKey());
            s.append(",[");
            boolean f2 = true;
            for (String out : i.getValue()) {
                if (!f2) s.append(',');
                f2 = false;
                quoteRaw(s, out);
            }
            s.append("])");
        }
        s.append("],[");
        first = true;
        for (String src : inputSrcs) {
            if (!first) s.append(',');
            first = false;
            quoteRaw(s, src);
        }
        s.append("],");
        quoteRaw(s, platform);
        s.append(',');
        quote(s, builder);
        s.append(",[");
        first = true;
        for (String arg : args) {
            if (!first) s.append(',');
            first = false;
            quote(s, arg);
        }
        s.append("],[");
        first = true;
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (!first) s.append(',');
            first = false;
            s.append('(');
            quote(s, e.getKey());
            s.append(',');
            quote(s, maskOutputs && outputs.containsKey(e.getKey()) ? "" : e.getValue());
            s.append(')');
        }
        s.append("])");
        return s.toString();
    }

    private static void quoteRaw(StringBuilder s, String str) {
        s.append('"').append(str).append('"');
    }

    private static void quote(StringBuilder s, String str) {
        s.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"', '\\' -> s.append('\\').append(c);
                case '\n' -> s.append("\\n");
                case '\r' -> s.append("\\r");
                case '\t' -> s.append("\\t");
                default -> s.append(c);
            }
        }
        s.append('"');
    }

    /** Reads a derivation in the ATerm format; an IllegalArgumentException says where it is wrong. */
    public static Derivation parse(String name, String s) {
        Derivation drv = new Derivation(name);
        ATerm p = new ATerm(s);
        p.expect("Derive([");
        while (!p.eat(']')) {
            p.eat(',');
            p.expect("(");
            String output = p.string();
            p.expect(",");
            String path = p.string();
            p.expect(",");
            String algo = p.string();
            p.expect(",");
            String hash = p.string();
            p.expect(")");
            drv.outputs.put(output, new Output(path, algo, hash));
        }
        p.expect(",[");
        while (!p.eat(']')) {
            p.eat(',');
            p.expect("(");
            String input = p.string();
            p.expect(",[");
            TreeSet<String> outs = new java.util.TreeSet<>();
            while (!p.eat(']')) {
                p.eat(',');
                outs.add(p.string());
            }
            p.expect(")");
            drv.inputDrvs.put(input, outs);
        }
        p.expect(",[");
        while (!p.eat(']')) {
            p.eat(',');
            drv.inputSrcs.add(p.string());
        }
        p.expect(",");
        drv.platform = p.string();
        p.expect(",");
        drv.builder = p.string();
        p.expect(",[");
        while (!p.eat(']')) {
            p.eat(',');
            drv.args.add(p.string());
        }
        p.expect(",[");
        while (!p.eat(']')) {
            p.eat(',');
            p.expect("(");
            String k = p.string();
            p.expect(",");
            drv.env.put(k, p.string());
            p.expect(")");
        }
        p.expect(")");
        return drv;
    }

    private static final class ATerm {
        final String s;
        int pos;

        ATerm(String s) {
            this.s = s;
        }

        IllegalArgumentException fail() {
            return new IllegalArgumentException("unexpected input at offset " + pos);
        }

        void expect(String t) {
            if (!s.startsWith(t, pos)) throw fail();
            pos += t.length();
        }

        boolean eat(char c) {
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        String string() {
            expect("\"");
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw fail();
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    sb.append(switch (e) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> e;
                    });
                } else {
                    sb.append(c);
                }
            }
        }
    }
}
