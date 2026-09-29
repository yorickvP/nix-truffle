package nixtruffle.fetch;

import nixtruffle.fs.Fs;
import nixtruffle.runtime.Bytes;
import nixtruffle.store.Hash;
import nixtruffle.store.Nar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code git} inputs (a port of libfetchers' {@code git.cc}). A local repository without a ref or
 * rev is read from its working tree (tracked files only; a dirty tree gets a {@code dirtyRev}
 * instead of a {@code rev}); anything else is a commit, from the repository itself or from a
 * bare clone in nix-truffle's cache.
 */
final class GitScheme implements InputScheme {
    static final String NULL_REV = "0000000000000000000000000000000000000000";

    @Override
    public String name() {
        return "git";
    }

    @Override
    public Input inputFromURL(Fetcher f, Url url, boolean requireTree) {
        if (!url.scheme.equals("git") && !"git".equals(Url.parseScheme(url.scheme)[0])) return null;
        Url url2 = url.copy();
        url2.query.clear();
        Attrs attrs = Attrs.of("type", "git");
        for (var e : url.query.entrySet()) {
            String name = e.getKey();
            switch (name) {
                case "rev", "ref", "keytype", "publicKey", "publicKeys" -> attrs.put(name, e.getValue());
                case "shallow", "submodules", "lfs", "exportIgnore", "allRefs", "verifyCommit" -> attrs.put(name, e.getValue().equals("1"));
                default -> url2.query.put(name, e.getValue());
            }
        }
        attrs.put("url", url2.toString());
        return inputFromAttrs(f, attrs);
    }

    @Override
    public Set<String> allowedAttrs() {
        return Set.of("url", "ref", "rev", "shallow", "submodules", "lfs", "exportIgnore", "lastModified", "revCount",
                "narHash", "allRefs", "name", "dirtyRev", "dirtyShortRev", "verifyCommit", "keytype", "publicKey", "publicKeys");
    }

    @Override
    public Input inputFromAttrs(Fetcher f, Attrs attrs) {
        for (String name : attrs.keySet()) {
            if (Set.of("verifyCommit", "keytype", "publicKey", "publicKeys").contains(name) && !f.settings.isEnabled("verified-fetches")) {
                throw new FetchException("experimental Nix feature 'verified-fetches' is disabled; add '--extra-experimental-features verified-fetches' to enable it");
            }
        }
        attrs.getBool("verifyCommit");
        String ref = attrs.getStr("ref");
        if (ref != null && !RefNames.isLegal(ref)) throw new FetchException.BadUrl("invalid Git branch/tag name '" + ref + "'");
        attrs.put("url", Url.fixGitUrl(attrs.requireStr("url")).toString());
        attrs.getBool("shallow");
        attrs.getBool("submodules");
        attrs.getBool("allRefs");
        return new Input(attrs, this);
    }

    @Override
    public Url toURL(Input input) {
        Url url = Url.parse(input.attrs.requireStr("url"), false);
        if (!url.scheme.equals("git")) url.scheme = "git+" + url.scheme;
        String rev = input.getRev();
        if (rev != null) url.query.put("rev", rev);
        String ref = input.getRef();
        if (ref != null) url.query.put("ref", ref);
        if (input.attrs.getBool("shallow", false)) url.query.put("shallow", "1");
        if (input.attrs.getBool("lfs", false)) url.query.put("lfs", "1");
        if (input.attrs.getBool("submodules", false)) url.query.put("submodules", "1");
        if (input.attrs.getBool("exportIgnore", false)) url.query.put("exportIgnore", "1");
        if (input.attrs.getBool("verifyCommit", false)) url.query.put("verifyCommit", "1");
        return url;
    }

    @Override
    public Input applyOverrides(Input input, String ref, String rev) {
        Attrs a = input.attrs.copy();
        if (rev != null) a.put("rev", rev);
        if (ref != null) a.put("ref", ref);
        Input res = input.withAttrs(a);
        if (res.getRef() == null && res.getRev() != null) {
            throw new FetchException("Git input '" + res + "' has a commit hash but no branch/tag name");
        }
        return res;
    }

    @Override
    public boolean isLocked(Fetcher f, Input input) {
        String rev = input.getRev();
        return rev != null && !rev.equals(NULL_REV);
    }

    @Override
    public String getSourcePath(Input input) {
        return repoInfo(input).path;
    }

    @Override
    public void putFile(Input input, String relPath, byte[] contents) {
        RepoInfo repo = repoInfo(input);
        if (repo.path == null) {
            throw new FetchException("cannot commit '/" + relPath + "' to Git repository '" + input + "' because it's not a working tree");
        }
        try {
            Fs.writeFile(repo.path + "/" + relPath, contents, 0666);
        } catch (IOException e) {
            throw new FetchException("cannot write '" + repo.path + "/" + relPath + "': " + e.getMessage());
        }
        GitRepo r = GitRepo.open(repo.path);
        // A file that isn't ignored is added (as intent-to-add, so that it's part of the tree).
        if (r.git(List.of("check-ignore", "--quiet", Bytes.toJava(relPath)), null, false).status() != 0) {
            r.git(List.of("add", "--intent-to-add", "--", Bytes.toJava(relPath)), null, true);
        }
    }

    // ---------------------------------------------------------- repo info

    /** A local repository ({@code path}) or a remote URL; the working tree state for local ones. */
    record RepoInfo(String path, Url url, GitRepo.WorkdirInfo workdir) {
        String location() {
            return path != null ? path : url.toString();
        }
    }

    private static RepoInfo repoInfo(Input input) {
        String rev = input.getRev();
        if (rev != null && rev.length() != 40 && rev.length() != 64) {
            throw new FetchException("Hash '" + rev + "' is not supported by Git. Supported types are sha1 and sha256.");
        }
        Url url = Url.parse(input.attrs.requireStr("url"), false);
        String local = null;
        if (url.scheme.equals("file")) {
            String path = Url.urlPathToPath(url.path);
            boolean bare;
            try {
                bare = Fs.maybeStat(path) != null && Fs.maybeLstat(path + "/.git") == null;
            } catch (IOException e) {
                bare = false;
            }
            if (!bare) local = path.startsWith("/") ? path : nixtruffle.NixLanguage.cwd() + "/" + path;
            else url.query.clear();
        }
        url.query.remove("dir");
        if (local != null) {
            GitRepo.WorkdirInfo wd = input.getRef() == null && input.getRev() == null ? GitRepo.open(local).workdirInfo() : null;
            return new RepoInfo(local, null, wd);
        }
        return new RepoInfo(null, url, null);
    }

    private static String cachePath(String url, boolean shallow) {
        return Cache.cacheDir() + "/git-v1/" + Hash.sha256(url).base32() + (shallow ? "-shallow" : "");
    }

    // ------------------------------------------------------------- fetching

    @Override
    public Result fetch(Fetcher f, Input input0) {
        Input input = input0.withAttrs(input0.attrs.copy());
        if (input.attrs.getBool("exportIgnore", false) && input.attrs.getBool("submodules", false)) {
            throw new FetchException("exportIgnore and submodules are not supported together yet");
        }
        if (input.attrs.getBool("lfs", false)) throw new FetchException("fetching Git LFS files is not supported by nix-truffle");
        RepoInfo repo = repoInfo(input);
        return input.getRef() != null || input.getRev() != null || repo.path == null
                ? fromCommit(f, repo, input)
                : fromWorkdir(f, repo, input);
    }

    private long lastModified(Fetcher f, GitRepo repo, String rev) {
        Attrs key = Attrs.of("rev", rev);
        Cache.Entry e = f.cache.lookup("gitLastModified", key);
        if (e != null) return e.value().getInt("lastModified");
        long lm = repo.lastModified(rev);
        f.cache.upsert("gitLastModified", key, Attrs.of("lastModified", lm));
        return lm;
    }

    private Attrs.Lazy lazyRevCount(Fetcher f, GitRepo repo, String rev) {
        return new Attrs.Lazy(() -> {
            if (repo.isShallow()) throw new FetchException("'" + repo.path + "' is a shallow Git repository, so 'revCount' is not available");
            Attrs key = Attrs.of("rev", rev);
            Cache.Entry e = f.cache.lookup("gitRevCount", key);
            if (e != null) return e.value().getInt("revCount");
            long n = repo.revCount(rev);
            f.cache.upsert("gitRevCount", key, Attrs.of("revCount", n));
            return n;
        });
    }

    private String defaultRef(Fetcher f, RepoInfo repo, boolean shallow) {
        String head = repo.path != null ? GitRepo.open(repo.path).workdirRef() : readHeadCached(f, repo.url.toString(), shallow);
        if (head == null) {
            System.err.println("warning: could not read HEAD ref from repo at '" + Bytes.toJava(repo.location()) + "', using 'master'");
            return "master";
        }
        return head;
    }

    private String readHeadCached(Fetcher f, String url, boolean shallow) {
        String cacheDir = cachePath(url, shallow);
        String cached = null;
        try {
            Fs.Stat st = Fs.maybeStat(cacheDir + "/HEAD");
            if (st != null) {
                cached = GitRepo.readRemoteHead(cacheDir);
                if (cached != null && st.mtime() + f.tarballTtl() > System.currentTimeMillis() / 1000) return cached;
            }
        } catch (IOException ignored) {
            // no cached HEAD
        }
        String ref = GitRepo.readRemoteHead(url);
        if (ref != null) return ref;
        if (cached != null) {
            System.err.println("warning: could not get HEAD ref for repository '" + Bytes.toJava(url) + "'; using expired cached ref '" + Bytes.toJava(cached) + "'");
        }
        return cached;
    }

    private Result fromCommit(Fetcher f, RepoInfo repoInfo, Input input) {
        String origRev = input.getRev();
        String originalRef = input.getRef();
        boolean shallow = input.attrs.getBool("shallow", false);
        String ref = originalRef != null ? originalRef : defaultRef(f, repoInfo, shallow);
        input.attrs.put("ref", ref);
        GitRepo repo;
        if (repoInfo.path != null) {
            repo = GitRepo.open(repoInfo.path);
            if (origRev == null) input.attrs.put("rev", repo.resolveRef(ref));
        } else {
            String url = repoInfo.url.toString();
            String cacheDir = cachePath(url, shallow);
            repo = GitRepo.openBare(cacheDir);
            String localRefFile = ref.startsWith("refs/") ? cacheDir + "/" + ref : cacheDir + "/refs/heads/" + ref;
            boolean allRefs = input.attrs.getBool("allRefs", false);
            boolean doFetch;
            long now = System.currentTimeMillis() / 1000;
            try {
                if (origRev != null) {
                    doFetch = !repo.hasObject(origRev);
                } else if (allRefs) {
                    doFetch = true;
                } else {
                    Fs.Stat st = Fs.maybeStat(localRefFile);
                    doFetch = st == null || st.mtime() + f.tarballTtl() <= now;
                }
                if (doFetch) {
                    String fetchRef = allRefs ? "refs/*:refs/*"
                            : origRev != null ? origRev
                            : ref.startsWith("refs/") ? ref + ":" + ref
                            : ref.equals("HEAD") ? "HEAD:HEAD"
                            : "refs/heads/" + ref + ":refs/heads/" + ref;
                    try {
                        repo.fetch(url, fetchRef, shallow);
                    } catch (FetchException e) {
                        if (Fs.maybeLstat(localRefFile) == null) throw e;
                        System.err.println("warning: could not update local clone of Git repository '" + Bytes.toJava(url) + "'; continuing with the most recent version");
                    }
                    if (origRev == null && Fs.maybeLstat(localRefFile) != null) {
                        // tarball-ttl counts from now.
                        Fs.touch(localRefFile);
                    }
                    if (originalRef == null) {
                        GitRepo.run(null, List.of("-C", Bytes.toJava(cacheDir), "--git-dir", ".", "symbolic-ref", "--", "HEAD", Bytes.toJava(ref)), null, false);
                    }
                }
            } catch (IOException e) {
                throw new FetchException(e.getMessage());
            }
            if (origRev != null) {
                if (!repo.hasObject(origRev)) {
                    throw new FetchException("Cannot find Git revision '" + origRev + "' in ref '" + ref + "' of repository '" + url
                            + "'! Please make sure that the rev exists on the ref you've specified or add allRefs = true; to fetchGit.");
                }
            } else {
                input.attrs.put("rev", repo.resolveRef(ref.startsWith("refs/") || ref.equals("HEAD") ? ref : "refs/heads/" + ref));
            }
        }
        String rev = input.getRev();
        if (!input.attrs.containsKey("lastModified")) input.attrs.put("lastModified", lastModified(f, repo, rev));
        if (!shallow && !input.attrs.containsKey("revCount")) input.attrs.put("revCount", lazyRevCount(f, repo, rev));

        boolean exportIgnore = input.attrs.getBool("exportIgnore", false);
        String tmp = Fetcher.tempDir("git");
        try {
            String root = tmp + "/tree";
            Fs.mkdir(root, 0755);
            repo.exportTree(rev, root, exportIgnore);
            if (input.attrs.getBool("submodules", false)) addSubmodules(f, repo, rev, ref, root, exportIgnore);
            return new Result(f.addTree(root, input.getName(), null), input);
        } catch (IOException e) {
            throw new FetchException("cannot export revision '" + rev + "' of Git repository '" + repoInfo.location() + "': " + e.getMessage());
        } finally {
            Fetcher.deleteTemp(tmp);
        }
    }

    /** Fetches the submodules of commit {@code rev} and puts their trees in place. */
    private void addSubmodules(Fetcher f, GitRepo repo, String rev, String ref, String root, boolean exportIgnore) throws IOException {
        if (Fs.maybeLstat(root + "/.gitmodules") == null) return;
        Map<String, Map<String, String>> modules = repo.parseGitmodules(root + "/.gitmodules");
        for (var m : modules.entrySet()) {
            String path = m.getKey();
            GitRepo.Output o = repo.git(List.of("ls-tree", "-z", rev, "--", Bytes.toJava(path)), null, true);
            String entry = Bytes.of(o.stdout()).replace("\0", "");
            if (!entry.startsWith("160000 commit ")) continue;
            String subRev = entry.substring("160000 commit ".length(), "160000 commit ".length() + 40);
            Attrs attrs = Attrs.of("type", "git", "url", resolveSubmoduleUrl(repo, m.getValue().get("url")), "rev", subRev,
                    "exportIgnore", exportIgnore, "submodules", true, "allRefs", true);
            String branch = m.getValue().get("branch");
            if (branch != null && !branch.isEmpty()) attrs.put("ref", branch.equals(".") ? ref : branch);
            Fetcher.Fetched sub = Input.fromAttrs(f, attrs).fetch(f);
            copyTree(sub.storePath(), root + "/" + path);
        }
    }

    private static String resolveSubmoduleUrl(GitRepo repo, String url) {
        if (!url.startsWith("./") && !url.startsWith("../")) return url;
        GitRepo.Output o = repo.git(List.of("config", "remote.origin.url"), null, false);
        String base = o.status() == 0 ? Bytes.of(o.stdout()).strip() : repo.path;
        String result = base;
        for (String part : url.split("/")) {
            if (part.equals(".")) continue;
            if (part.equals("..")) {
                int slash = result.lastIndexOf('/');
                result = slash < 0 ? result : result.substring(0, slash);
            } else {
                result = result + "/" + part;
            }
        }
        return result;
    }

    /** Copies a file tree (replacing what's at {@code dest}). */
    static void copyTree(String src, String dest) throws IOException {
        Fs.deleteTree(dest);
        Fs.Stat st = Fs.lstat(src);
        if (st.isSymlink()) {
            Fs.symlink(Fs.readLink(src), dest);
        } else if (st.isDirectory()) {
            Fs.mkdir(dest, 0755);
            for (String name : Fs.list(src)) copyTree(src + "/" + name, dest + "/" + name);
        } else {
            Fs.writeFile(dest, Fs.readFile(src), st.isExecutable() ? 0755 : 0644);
        }
    }

    private Result fromWorkdir(Fetcher f, RepoInfo repoInfo, Input input) {
        String repoPath = repoInfo.path;
        GitRepo repo = GitRepo.open(repoPath);
        GitRepo.WorkdirInfo wd = repoInfo.workdir;
        boolean exportIgnore = input.attrs.getBool("exportIgnore", false);
        boolean submodules = input.attrs.getBool("submodules", false);
        Set<String> files = new HashSet<>(wd.files());
        if (exportIgnore) {
            List<String> paths = new ArrayList<>(files);
            Set<String> dirs = new HashSet<>();
            for (String p : files) {
                for (int i = p.indexOf('/'); i >= 0; i = p.indexOf('/', i + 1)) dirs.add(p.substring(0, i));
            }
            paths.addAll(dirs);
            Set<String> ignored = repo.exportIgnored(paths, null);
            files.removeIf(p -> {
                if (ignored.contains(p)) return true;
                for (int i = p.indexOf('/'); i >= 0; i = p.indexOf('/', i + 1)) if (ignored.contains(p.substring(0, i))) return true;
                return false;
            });
        }
        boolean dirty = wd.isDirty();
        Fetcher.Tree tree;
        if (submodules && !wd.submodules().isEmpty()) {
            // Put the working tree and the submodules' trees together.
            String tmp = Fetcher.tempDir("git-workdir");
            try {
                String root = tmp + "/tree";
                copyAllowed(repoPath, root, files);
                for (String sub : wd.submodules()) {
                    Attrs attrs = Attrs.of("type", "git", "url", repoPath + "/" + sub, "exportIgnore", exportIgnore, "submodules", true);
                    Fetcher.Fetched s = Input.fromAttrs(f, attrs).fetch(f);
                    if (s.locked().getRev() == null) dirty = true;
                    copyTree(s.storePath(), root + "/" + sub);
                }
                tree = f.addTree(root, input.getName(), null);
            } catch (IOException e) {
                throw new FetchException(e.getMessage());
            } finally {
                Fetcher.deleteTemp(tmp);
            }
        } else {
            tree = f.addTree(repoPath, input.getName(), allowListFilter(repoPath, files));
        }
        String head = wd.headRev();
        if (!dirty) {
            String ref = repo.workdirRef();
            if (ref != null) input.attrs.put("ref", ref);
            String rev = head != null ? head : NULL_REV;
            input.attrs.put("rev", rev);
            if (!input.attrs.getBool("shallow", false)) {
                input.attrs.put("revCount", head == null ? (Object) 0L : lazyRevCount(f, repo, rev));
            }
        } else {
            if (!f.settings.getBool("allow-dirty")) throw new FetchException("Git tree '" + repoPath + "' is dirty");
            if (f.settings.getBool("warn-dirty")) System.err.println("warning: Git tree '" + Bytes.toJava(repoPath) + "' is dirty");
            if (head != null) {
                input.attrs.put("dirtyRev", head + "-dirty");
                input.attrs.put("dirtyShortRev", head.substring(0, 7) + "-dirty");
            }
        }
        input.attrs.put("lastModified", head != null ? lastModified(f, repo, head) : 0L);
        return new Result(tree, input);
    }

    /** Only the given files (relative paths) and the directories leading to them. */
    static Nar.Filter allowListFilter(String root, Set<String> files) {
        Set<String> allowed = new HashSet<>(files);
        for (String p : files) {
            for (int i = p.indexOf('/'); i >= 0; i = p.indexOf('/', i + 1)) allowed.add(p.substring(0, i));
        }
        String prefix = root.endsWith("/") ? root : root + "/";
        return (path, type) -> allowed.contains(path.substring(prefix.length()));
    }

    private static void copyAllowed(String repoPath, String dest, Set<String> files) throws IOException {
        Fs.mkdir(dest, 0755);
        for (String p : new java.util.TreeSet<>(files)) {
            String target = dest + "/" + p;
            int slash = target.lastIndexOf('/');
            Fs.mkdirs(target.substring(0, slash));
            if (Fs.maybeLstat(repoPath + "/" + p) != null) copyTree(repoPath + "/" + p, target);
        }
    }
}
