package nixtruffle.fetch;

import nixtruffle.fs.Fs;
import nixtruffle.runtime.Bytes;
import nixtruffle.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Flake registries: user, system and global lookups of indirect inputs ({@code registry.cc}). */
public final class Registry {
    public enum Type { FLAG, USER, SYSTEM, GLOBAL, CUSTOM }

    /** How much of the registries a lookup may use; {@code LIMITED} is the flag and global ones. */
    public enum Use { NO, LIMITED, ALL }

    record Entry(Input from, Input to, Attrs extraAttrs, boolean exact) {}

    final Type type;
    final List<Entry> entries = new ArrayList<>();

    Registry(Type type) {
        this.type = type;
    }

    static Registry read(Fetcher f, String path, Type type) {
        Registry r = new Registry(type);
        try {
            if (path == null || Fs.maybeStat(path) == null) return r;
            Map<String, Object> json = Json.obj(Json.parse(Bytes.of(Fs.readFile(path))));
            Object version = json.getOrDefault("version", 0L);
            if (!Long.valueOf(2).equals(version)) {
                throw new FetchException("flake registry '" + path + "' has unsupported version " + version);
            }
            for (Object o : Json.arr(json.get("flakes"))) {
                Map<String, Object> i = Json.obj(o);
                Attrs to = Attrs.Json.fromJson(i.get("to"));
                Attrs extra = new Attrs();
                if (to.containsKey("dir")) extra.put("dir", to.remove("dir"));
                r.entries.add(new Entry(Input.fromAttrs(f, Attrs.Json.fromJson(i.get("from"))), Input.fromAttrs(f, to), extra,
                        Boolean.TRUE.equals(i.get("exact"))));
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("warning: cannot parse flake registry '" + Bytes.toJava(path) + "': " + e.getMessage());
        } catch (FetchException e) {
            System.err.println("warning: cannot read flake registry '" + Bytes.toJava(path) + "': " + Bytes.toJava(e.getMessage()));
        }
        return r;
    }

    /** The registries in lookup order, read on first use. */
    static final class Registries {
        private final Fetcher f;
        private List<Registry> all;

        Registries(Fetcher f) {
            this.f = f;
        }

        List<Registry> get() {
            if (all == null) {
                all = new ArrayList<>();
                all.add(new Registry(Type.FLAG));
                String xdg = System.getenv("XDG_CONFIG_HOME");
                String config = xdg != null && !xdg.isEmpty() ? xdg : System.getProperty("user.home") + "/.config";
                all.add(read(f, Bytes.fromJava(config + "/nix/registry.json"), Type.USER));
                String confDir = System.getenv().getOrDefault("NIX_CONF_DIR", "/etc/nix");
                all.add(read(f, Bytes.fromJava(confDir + "/registry.json"), Type.SYSTEM));
                all.add(global());
            }
            return all;
        }

        private Registry global() {
            String path = Bytes.fromJava(f.settings.get("flake-registry"));
            if (path.isEmpty()) return new Registry(Type.GLOBAL);
            if (!path.startsWith("/")) {
                try {
                    path = CurlScheme.downloadFile(f, path, "flake-registry.json", Map.of()).storePath();
                } catch (FetchException e) {
                    System.err.println("warning: cannot download flake registry: " + Bytes.toJava(e.getMessage()));
                    return new Registry(Type.GLOBAL);
                }
            }
            return read(f, path, Type.GLOBAL);
        }
    }

    /** {@code overrideRegistry}: an entry in the flag registry ({@code --override-flake}, {@code --inputs-from}). */
    public static void override(Fetcher f, Input from, Input to, Attrs extraAttrs) {
        for (Registry r : f.registries().get()) {
            if (r.type == Type.FLAG) r.entries.add(new Entry(from, to, extraAttrs, false));
        }
    }

    /** {@code lookupInRegistries}: the direct input an indirect one stands for, and extra attributes (dir). */
    public static Object[] lookup(Fetcher f, Input input0, Use use) {
        Attrs extra = new Attrs();
        Input input = input0;
        if (use == Use.NO) return new Object[] {input, extra};
        int n = 0;
        restart:
        while (true) {
            if (++n > 100) throw new FetchException("cycle detected in flake registry for '" + input + "'");
            for (Registry r : f.registries().get()) {
                if (use == Use.LIMITED && r.type != Type.FLAG && r.type != Type.GLOBAL) continue;
                for (Entry e : r.entries) {
                    if (e.exact) {
                        if (e.from.sameAs(input)) {
                            input = e.to;
                            extra = e.extraAttrs;
                            continue restart;
                        }
                    } else if (e.from.contains(input)) {
                        input = e.to.applyOverrides(e.from.getRef() == null ? input.getRef() : null,
                                e.from.getRev() == null ? input.getRev() : null);
                        extra = e.extraAttrs;
                        continue restart;
                    }
                }
            }
            break;
        }
        if (!input.isDirect()) throw new FetchException("cannot find flake '" + input + "' in the flake registries");
        return new Object[] {input, extra};
    }
}
