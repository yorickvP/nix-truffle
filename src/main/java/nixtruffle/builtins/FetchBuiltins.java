package nixtruffle.builtins;

import nixtruffle.NixContext;
import nixtruffle.Settings;
import nixtruffle.fetch.Attrs;
import nixtruffle.fetch.FetchException;
import nixtruffle.fetch.Fetcher;
import nixtruffle.fetch.Input;
import nixtruffle.fetch.Registry;
import nixtruffle.fetch.Url;
import nixtruffle.fs.Fs;
import nixtruffle.runtime.Apply;
import nixtruffle.runtime.Builtin;
import nixtruffle.runtime.Bytes;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixNull;
import nixtruffle.runtime.NixPath;
import nixtruffle.runtime.NixString;
import nixtruffle.runtime.Values;
import nixtruffle.store.Hash;
import nixtruffle.store.StorePaths;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static nixtruffle.runtime.Thunk.force;
import static nixtruffle.runtime.Values.stringNoCtx;

/** The fetchers ({@code libexpr/primops/fetchTree.cc}, {@code fetchMercurial.cc}). */
final class FetchBuiltins {
    private FetchBuiltins() {}

    private static NixException error(String message) {
        return NixException.error(message, null);
    }

    static Fetcher fetcher() {
        return NixContext.get(null).fetcher();
    }

    /** Runs fetcher code, turning its errors into evaluation errors. */
    static <T> T run(java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (FetchException e) {
            throw error(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw error(Bytes.fromJava(String.valueOf(e.getMessage())));
        }
    }

    record Params(boolean emptyRevFallback, boolean allowNameArgument, boolean isFetchGit, boolean isFinal) {}

    static void install() {
        Builtins.def("fetchTree", 1, a -> fetchTree(a[0], new Params(false, false, false, false)));
        Builtins.def("fetchGit", 1, a -> fetchTree(a[0], new Params(true, true, true, false)));
        Builtins.def("fetchTarball", 1, a -> fetch(a[0], "fetchTarball", true, "source"));
        Builtins.def("fetchurl", 1, a -> fetch(a[0], "fetchurl", false, ""));
        Builtins.def("fetchMercurial", 1, a -> fetchMercurial(a[0]));
    }

    /** The fetchTree used by call-flake.nix: the input is locked already. */
    static Object fetchFinalTree(Object arg) {
        return fetchTree(arg, new Params(false, false, false, true));
    }

    // ------------------------------------------------------------ fetchTree

    private static Object fetchTree(Object arg, Params params) {
        String fetcherName = params.isFetchGit ? "fetchGit" : "fetchTree";
        String type = params.isFetchGit ? "git" : null;
        Object v = force(arg);
        Fetcher f = fetcher();
        Input input;
        if (v instanceof NixAttrs args) {
            Attrs attrs = new Attrs();
            Object aType = args.getRaw("type");
            if (aType != null) {
                if (type != null) throw error("unexpected argument 'type'");
                type = stringNoCtx(aType);
            } else if (type == null) {
                throw error("argument 'type' is missing in call to '" + fetcherName + "'");
            }
            attrs.put("type", type);
            for (int i = 0; i < args.size(); i++) {
                String name = args.keys[i];
                if (name.equals("type")) continue;
                Object value = args.forceAt(i);
                if (value instanceof NixPath || NixString.is(value)) {
                    String s = Values.coerce(value, false, false, new TreeSet<>(), null);
                    attrs.put(name, params.isFetchGit && name.equals("url") ? run(() -> Url.fixGitUrl(s).toString()) : s);
                } else if (value instanceof Boolean b) {
                    attrs.put(name, b);
                } else if (value instanceof Long l) {
                    if (l < 0) throw error("negative value given for '" + fetcherName + "' argument '" + name + "': " + l);
                    attrs.put(name, l);
                } else if (name.equals("publicKeys")) {
                    throw error("experimental Nix feature 'verified-fetches' is disabled; add '--extra-experimental-features verified-fetches' to enable it");
                } else {
                    throw error("argument '" + name + "' to '" + fetcherName + "' is " + Values.typeName(value) + " while a string, Boolean or integer is expected");
                }
            }
            if (params.isFetchGit && !attrs.containsKey("exportIgnore") && !Boolean.TRUE.equals(attrs.get("submodules"))) {
                attrs.put("exportIgnore", true);
            }
            // fetchTree fetches git repositories shallowly by default.
            if (type.equals("git") && !params.isFetchGit && !attrs.containsKey("shallow")) attrs.put("shallow", true);
            if (!params.allowNameArgument && attrs.containsKey("name")) {
                throw error("argument 'name' isn’t supported in call to '" + fetcherName + "'");
            }
            input = run(() -> Input.fromAttrs(f, attrs));
        } else {
            String url = Values.coerce(v, false, false, new TreeSet<>(), null);
            if (params.isFetchGit) {
                Attrs attrs = Attrs.of("type", "git", "url", run(() -> Url.fixGitUrl(url).toString()), "exportIgnore", true);
                input = run(() -> Input.fromAttrs(f, attrs));
            } else {
                if (!f.flakesEnabled()) throw error("passing a string argument to '" + fetcherName + "' requires the 'flakes' experimental feature");
                input = run(() -> Input.fromURL(f, url, true));
            }
        }
        NixContext ctx = NixContext.get(null);
        if (!ctx.pureEval && !input.isDirect() && f.flakesEnabled()) {
            Input i = input;
            input = run(() -> (Input) Registry.lookup(f, i, Registry.Use.LIMITED)[0]);
        }
        if (ctx.pureEval && !input.isLocked(f)) {
            Input i = input;
            if (run(i::getNarHash) != null) {
                ctx.printErr("warning: Input '" + run(i::toString) + "' is unlocked (e.g. lacks a Git revision) but is checked by NAR hash. "
                        + "This is not reproducible and will break after garbage collection or when shared.");
            } else {
                throw error("in pure evaluation mode, '" + fetcherName + "' doesn't fetch unlocked input '" + run(i::toString) + "'");
            }
        }
        if (params.isFinal) {
            input.attrs.put("__final", true);
        } else if (input.isFinal()) {
            Input i = input;
            throw error("input '" + run(i::toString) + "' is not allowed to use the '__final' attribute");
        }
        Input in = input;
        Fetcher.Fetched fetched = run(() -> in.fetch(f));
        f.mount(fetched.storePath());
        return emitTreeAttrs(fetched.storePath(), fetched.locked(), params.emptyRevFallback, false);
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /** The attributes of a fetched tree: outPath, narHash, rev, lastModified, ... */
    static NixAttrs emitTreeAttrs(String storePath, Input input, boolean emptyRevFallback, boolean forceDirty) {
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("outPath", NixString.make(storePath, Set.of(storePath)));
        Hash narHash = run(input::getNarHash);
        if (narHash != null) m.put("narHash", narHash.sri());
        if (input.getType().equals("git")) m.put("submodules", input.attrs.getBool("submodules", false));
        if (!forceDirty) {
            String rev = input.getRev();
            if (rev != null) {
                m.put("rev", rev);
                m.put("shortRev", rev.substring(0, 7));
            } else if (emptyRevFallback) {
                m.put("rev", "0000000000000000000000000000000000000000");
                m.put("shortRev", "0000000");
            }
            Object revCount = input.attrs.get("revCount");
            if (revCount instanceof Attrs.Lazy lazy) {
                // Counting commits can be expensive: only when it's used.
                Builtin compute = new Builtin("revCount", 1, a -> run(lazy::get));
                m.put("revCount", Apply.lazy(compute, NixNull.INSTANCE));
            } else if (revCount != null) {
                m.put("revCount", revCount);
            } else if (emptyRevFallback) {
                m.put("revCount", 0L);
            }
        }
        String dirtyRev = input.attrs.getStr("dirtyRev");
        if (dirtyRev != null) {
            m.put("dirtyRev", dirtyRev);
            m.put("dirtyShortRev", input.attrs.getStr("dirtyShortRev"));
        }
        Long lastModified = input.getLastModified();
        if (lastModified != null) {
            m.put("lastModified", lastModified);
            m.put("lastModifiedDate", DATE.format(Instant.ofEpochSecond(lastModified)));
        }
        return NixAttrs.fromMap(m);
    }

    // ---------------------------------------------- fetchurl, fetchTarball

    private static Object fetch(Object arg, String who, boolean unpack, String defaultName) {
        Object v = force(arg);
        String url = null;
        Hash expectedHash = null;
        String name = defaultName;
        boolean isArgAttrs = v instanceof NixAttrs;
        boolean nameAttrPassed = false;
        if (v instanceof NixAttrs args) {
            for (int i = 0; i < args.size(); i++) {
                String n = args.keys[i];
                switch (n) {
                    case "url" -> url = stringNoCtx(args.values[i]);
                    case "sha256" -> {
                        String s = stringNoCtx(args.values[i]);
                        try {
                            expectedHash = Hash.parseAllowEmpty(s, "sha256");
                        } catch (IllegalArgumentException e) {
                            throw error(e.getMessage());
                        }
                    }
                    case "name" -> {
                        nameAttrPassed = true;
                        name = stringNoCtx(args.values[i]);
                    }
                    default -> throw error("unsupported argument '" + n + "' to '" + who + "'");
                }
            }
            if (url == null) throw error("'url' argument required");
        } else {
            url = stringNoCtx(v);
        }
        if (who.equals("fetchTarball")) url = Bytes.fromJava(Settings.resolvePseudoUrl(Bytes.toJava(url)));
        if (name.isEmpty()) name = Builtins.legacyBaseNameOf(url);
        String nameError = StorePaths.checkName(name);
        if (nameError != null) {
            String resolution = nameAttrPassed
                    ? "Please change the value for the 'name' attribute passed to '" + who + "', so that it can create a valid store path."
                    : isArgAttrs
                            ? "Please add a valid 'name' attribute to the argument for '" + who + "', so that it can create a valid store path."
                            : "Please pass an attribute set with 'url' and 'name' attributes to '" + who + "',  so that it can create a valid store path.";
            throw error("invalid store path name when fetching URL '" + url + "': " + nameError + ". " + resolution);
        }
        NixContext ctx = NixContext.get(null);
        if (ctx.settings.getBool("pure-eval") && expectedHash == null) throw error("in pure evaluation mode, '" + who + "' requires a 'sha256' argument");
        // Early exit if pinned and already in the store.
        if (expectedHash != null && expectedHash.algo().equals("sha256")) {
            String expectedPath = StorePaths.fixedOutputPath(unpack, expectedHash, name);
            if (fetcher().isValid(expectedPath)) {
                fetcher().addTempRoot(expectedPath);
                ctx.allowPath(expectedPath);
                return NixString.make(expectedPath, Set.of(expectedPath));
            }
        }
        Fetcher f = fetcher();
        String u = url;
        String nm = name;
        if (unpack) {
            Attrs attrs = Attrs.of("type", "tarball", "url", url, "name", name);
            if (expectedHash != null) attrs.put("narHash", expectedHash.sri());
            Fetcher.Fetched fetched = run(() -> Input.fromAttrs(f, attrs).fetch(f));
            f.mount(fetched.storePath());
            return NixString.make(fetched.storePath(), Set.of(fetched.storePath()));
        }
        String storePath = run(() -> fetchFile(f, u, nm));
        if (expectedHash != null) {
            Hash got;
            try {
                got = Hash.of(expectedHash.algo(), Fs.readFile(storePath));
            } catch (IOException e) {
                throw error(e.getMessage());
            }
            if (!got.equals(expectedHash)) {
                throw error("hash mismatch in file downloaded from '" + url + "':\n  specified: " + expectedHash.algo() + ":" + expectedHash.base32()
                        + "\n  got:       " + got.algo() + ":" + got.base32());
            }
        }
        ctx.allowPath(storePath);
        return NixString.make(storePath, Set.of(storePath));
    }

    private static String fetchFile(Fetcher f, String url, String name) {
        return f.downloadFile(url, name);
    }

    /** A lookup path URL: the tarball, unpacked in the store. */
    static String downloadTarballToStore(String url) {
        Fetcher f = fetcher();
        return run(() -> Input.fromAttrs(f, Attrs.of("type", "tarball", "url", url)).fetch(f).storePath());
    }

    // ------------------------------------------------------ fetchMercurial

    private static Object fetchMercurial(Object arg) {
        Object v = force(arg);
        String url = null;
        String rev = null;
        String ref = null;
        String name = "source";
        if (v instanceof NixAttrs args) {
            for (int i = 0; i < args.size(); i++) {
                String n = args.keys[i];
                switch (n) {
                    case "url" -> url = Values.coerce(args.values[i], false, false, new TreeSet<>(), null);
                    case "rev" -> {
                        String value = stringNoCtx(args.values[i]);
                        if (value.matches("[0-9a-fA-F]{40}")) rev = value.toLowerCase(); else ref = value;
                    }
                    case "name" -> name = stringNoCtx(args.values[i]);
                    default -> throw error("unsupported argument '" + n + "' to 'fetchMercurial'");
                }
            }
            if (url == null || url.isEmpty()) throw error("'url' argument required");
        } else {
            url = Values.coerce(v, false, false, new TreeSet<>(), null);
        }
        if (NixContext.get(null).pureEval && rev == null) throw error("in pure evaluation mode, 'fetchMercurial' requires a Mercurial revision");
        Attrs attrs = Attrs.of("type", "hg", "url", url.contains("://") ? url : "file://" + url, "name", name);
        if (ref != null) attrs.put("ref", ref);
        if (rev != null) attrs.put("rev", rev);
        Fetcher f = fetcher();
        Fetcher.Fetched fetched = run(() -> Input.fromAttrs(f, attrs).fetch(f));
        NixContext.get(null).allowPath(fetched.storePath());
        Input locked = fetched.locked();
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("outPath", NixString.make(fetched.storePath(), Set.of(fetched.storePath())));
        if (locked.getRef() != null) m.put("branch", locked.getRef());
        String rev2 = locked.getRev() != null ? locked.getRev() : "0000000000000000000000000000000000000000";
        m.put("rev", rev2);
        m.put("shortRev", rev2.substring(0, 12));
        if (locked.getRevCount() != null) m.put("revCount", locked.getRevCount());
        return NixAttrs.fromMap(m);
    }
}
