package nixtruffle.fetch;

import nixtruffle.fs.Fs;
import nixtruffle.runtime.Bytes;
import nixtruffle.store.Hash;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** {@code hg} inputs, through the {@code hg} command (like CppNix). */
final class MercurialScheme implements InputScheme {
    private static final Pattern REF = Pattern.compile("[a-zA-Z0-9@][a-zA-Z0-9_./@+-]*");

    @Override
    public String name() {
        return "hg";
    }

    @Override
    public Input inputFromURL(Fetcher f, Url url, boolean requireTree) {
        if (!Set.of("hg+http", "hg+https", "hg+ssh", "hg+file").contains(url.scheme)) return null;
        Url url2 = url.copy();
        url2.scheme = url.scheme.substring(3);
        url2.query.clear();
        Attrs attrs = Attrs.of("type", "hg");
        for (var e : url.query.entrySet()) {
            if (e.getKey().equals("rev") || e.getKey().equals("ref")) attrs.put(e.getKey(), e.getValue()); else url2.query.put(e.getKey(), e.getValue());
        }
        attrs.put("url", url2.toString());
        return inputFromAttrs(f, attrs);
    }

    @Override
    public Set<String> allowedAttrs() {
        return Set.of("url", "ref", "rev", "revCount", "narHash", "name");
    }

    @Override
    public Input inputFromAttrs(Fetcher f, Attrs attrs) {
        Url.parse(attrs.requireStr("url"), false);
        String ref = attrs.getStr("ref");
        if (ref != null && !REF.matcher(ref).matches()) throw new FetchException.BadUrl("invalid Mercurial branch/tag name '" + ref + "'");
        return new Input(attrs, this);
    }

    @Override
    public Url toURL(Input input) {
        Url url = Url.parse(input.attrs.requireStr("url"), false);
        url.scheme = "hg+" + url.scheme;
        String rev = input.getRev();
        if (rev != null) url.query.put("rev", rev);
        String ref = input.getRef();
        if (ref != null) url.query.put("ref", ref);
        return url;
    }

    @Override
    public Input applyOverrides(Input input, String ref, String rev) {
        Attrs a = input.attrs.copy();
        if (rev != null) a.put("rev", rev);
        if (ref != null) a.put("ref", ref);
        return input.withAttrs(a);
    }

    @Override
    public boolean isLocked(Fetcher f, Input input) {
        return input.getRev() != null;
    }

    @Override
    public String getSourcePath(Input input) {
        Url url = Url.parse(input.attrs.requireStr("url"), false);
        if (url.scheme.equals("file") && input.getRef() == null && input.getRev() == null) return Url.urlPathToPath(url.path);
        return null;
    }

    private static String hg(List<String> args) {
        GitRepo.Output o = run(args);
        if (o.status() != 0) throw new FetchException("hg " + Bytes.fromJava(String.join(" ", args)) + " failed: " + o.stderr());
        return Bytes.of(o.stdout()).strip();
    }

    /** Runs hg with {@code HGPLAIN}, for output that doesn't depend on the user's configuration. */
    private static GitRepo.Output run(List<String> args) {
        List<String> cmd = new ArrayList<>(List.of("hg"));
        cmd.addAll(args);
        return GitRepo.exec(cmd, java.util.Map.of("HGPLAIN", ""), null);
    }

    @Override
    public Result fetch(Fetcher f, Input input0) {
        Input input = input0.withAttrs(input0.attrs.copy());
        Url url = Url.parse(input.attrs.requireStr("url"), false);
        String actualUrl = url.scheme.equals("file") ? Url.urlPathToPath(url.path) : url.toString();
        String ref = input.getRef() != null ? input.getRef() : "default";
        input.attrs.putIfAbsent("ref", ref);
        String repoDir = actualUrl;
        if (!url.scheme.equals("file")) {
            repoDir = Cache.cacheDir() + "/hg-v1/" + Hash.sha256(actualUrl).base32();
            try {
                if (Fs.maybeLstat(repoDir + "/.hg") == null) {
                    Fs.mkdirs(repoDir.substring(0, repoDir.lastIndexOf('/')));
                    hg(List.of("clone", "--noupdate", Bytes.toJava(actualUrl), Bytes.toJava(repoDir)));
                } else if (input.getRev() == null || run(List.of("log", "-R", Bytes.toJava(repoDir), "-r", Bytes.toJava(input.getRev()), "--template", "1")).status() != 0) {
                    hg(List.of("pull", "-R", Bytes.toJava(repoDir), "--", Bytes.toJava(actualUrl)));
                }
            } catch (IOException e) {
                throw new FetchException(e.getMessage());
            }
        }
        String revSpec = input.getRev() != null ? input.getRev() : ref;
        String[] info = hg(List.of("log", "-R", Bytes.toJava(repoDir), "-r", Bytes.toJava(revSpec), "--template", "{node} {rev} {branch}")).split(" ");
        input.attrs.put("rev", info[0]);
        input.attrs.put("revCount", Long.parseLong(info[1]) + 1);
        String tmp = Fetcher.tempDir("hg");
        try {
            String root = tmp + "/tree";
            hg(List.of("archive", "-R", Bytes.toJava(repoDir), "-r", info[0], Bytes.toJava(root)));
            Fs.deleteTree(root + "/.hg_archival.txt");
            return new Result(f.addTree(root, input.getName(), null), input);
        } catch (IOException e) {
            throw new FetchException(e.getMessage());
        } finally {
            Fetcher.deleteTemp(tmp);
        }
    }
}
