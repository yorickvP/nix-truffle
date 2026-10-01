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
        Derivation drv = store.entries.get(drvPath) instanceof Store.Drv ? store.derivation(drvPath) : read(drvPath);
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
        try {
            return Derivation.parse(base.substring(0, base.length() - 4), s);
        } catch (IllegalArgumentException e) {
            throw NixException.error("error parsing derivation '" + drvPath + "': " + e.getMessage(), null);
        }
    }
}
