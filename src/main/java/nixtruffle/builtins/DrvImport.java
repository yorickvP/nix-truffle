package nixtruffle.builtins;

import nixtruffle.NixContext;
import nixtruffle.fs.Fs;
import nixtruffle.runtime.Bytes;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.NixString;
import nixtruffle.store.Derivation;
import nixtruffle.store.Store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code import} of a {@code .drv} file in the store: an attribute set describing it, which
 * {@code imported-drv-to-derivation.nix} turns into a derivation value.
 */
final class DrvImport {
    private DrvImport() {}

    static NixAttrs toValue(String drvPath) {
        Store store = NixContext.get(null).store;
        Derivation drv = store.entries.get(drvPath) instanceof Store.Drv d ? d.drv() : read(drvPath);
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("drvPath", NixString.make(drvPath, Set.of("=" + drvPath)));
        m.put("name", drv.env.getOrDefault("name", drv.name));
        List<Object> outputs = new ArrayList<>();
        for (var o : drv.outputs.entrySet()) {
            outputs.add(o.getKey());
            m.put(o.getKey(), NixString.make(o.getValue().path(), Set.of("!" + o.getKey() + "!" + drvPath)));
        }
        m.put("outputs", new NixList(outputs.toArray()));
        return NixAttrs.fromMap(m);
    }

    /** Reads a {@code .drv} file (the outputs and environment are all we need). */
    static Derivation read(String drvPath) {
        String s;
        try {
            s = Bytes.of(Fs.readFile(drvPath));
        } catch (IOException e) {
            throw NixException.error(e.getMessage(), null);
        }
        String base = drvPath.substring(drvPath.lastIndexOf('/') + 34);
        Derivation drv = new Derivation(base.substring(0, base.length() - 4));
        ATerm p = new ATerm(s, drvPath);
        p.expect("Derive([");
        while (!p.eat(']')) {
            p.eat(',');
            p.expect("(");
            String name = p.string();
            p.expect(",");
            String path = p.string();
            p.expect(",");
            String algo = p.string();
            p.expect(",");
            String hash = p.string();
            p.expect(")");
            drv.outputs.put(name, new Derivation.Output(path, algo, hash));
        }
        p.expect(",[");
        while (!p.eat(']')) {
            p.eat(',');
            p.expect("(");
            String input = p.string();
            p.expect(",[");
            java.util.TreeSet<String> outs = new java.util.TreeSet<>();
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
        final String file;
        int pos;

        ATerm(String s, String file) {
            this.s = s;
            this.file = file;
        }

        NixException fail() {
            return NixException.error("error parsing derivation '" + file + "': unexpected input at offset " + pos, null);
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
