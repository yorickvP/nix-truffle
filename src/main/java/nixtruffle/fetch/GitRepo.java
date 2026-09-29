package nixtruffle.fetch;

import nixtruffle.fs.Fs;
import nixtruffle.runtime.Bytes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A git repository, driven through the {@code git} CLI (CppNix uses libgit2; Lix the CLI).
 * Paths are byte strings; git's {@code -z} output keeps file names byte-exact.
 */
final class GitRepo {
    /** The working tree (or, for a bare repository, the repository itself). */
    final String path;
    /** {@code --git-dir}: {@code .git}, or {@code .} for bare repositories. */
    final String gitDir;

    GitRepo(String path, String gitDir) {
        this.path = path;
        this.gitDir = gitDir;
    }

    static GitRepo open(String path) {
        return new GitRepo(path, ".git");
    }

    /** A bare repository (the cache of a remote), created if needed. */
    static GitRepo openBare(String path) {
        try {
            if (Fs.maybeStat(path + "/HEAD") == null) {
                Fs.mkdirs(path);
                run(null, List.of("init", "--bare", "--quiet", Bytes.toJava(path)), null, true);
            }
        } catch (IOException e) {
            throw new FetchException("cannot create Git repository '" + path + "': " + e.getMessage());
        }
        return new GitRepo(path, ".");
    }

    // ------------------------------------------------------------ running

    record Output(int status, byte[] stdout, String stderr) {}

    /** Runs {@code git -C path --git-dir gitDir args...}. */
    Output git(List<String> args, byte[] stdin, boolean check) {
        List<String> full = new ArrayList<>(List.of("-C", Bytes.toJava(path), "--git-dir", gitDir));
        full.addAll(args);
        return run(path, full, stdin, check);
    }

    static Output run(String where, List<String> args, byte[] stdin, boolean check) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(args);
        // Don't let git take locks or ask for credentials interactively.
        Output o = exec(cmd, Map.of("GIT_OPTIONAL_LOCKS", "0", "GIT_TERMINAL_PROMPT", "0"), stdin);
        if (check && o.status != 0) {
            throw new FetchException("git " + Bytes.fromJava(String.join(" ", args)) + " failed" + (o.stderr.isEmpty() ? "" : ": " + o.stderr));
        }
        return o;
    }

    /** Runs a program, feeding it {@code stdin} and collecting its output. */
    static Output exec(List<String> cmd, Map<String, String> env, byte[] stdin) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(env);
        try {
            Process p = pb.start();
            Thread writer = null;
            if (stdin != null) {
                writer = new Thread(() -> {
                    try (OutputStream o = p.getOutputStream()) {
                        o.write(stdin);
                    } catch (IOException ignored) {
                        // git exited early; its status tells
                    }
                });
                writer.start();
            } else {
                p.getOutputStream().close();
            }
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread errReader = new Thread(() -> {
                try (InputStream e = p.getErrorStream()) {
                    e.transferTo(err);
                } catch (IOException ignored) {
                    // nothing to report
                }
            });
            errReader.start();
            byte[] out = p.getInputStream().readAllBytes();
            int status = p.waitFor();
            errReader.join();
            if (writer != null) writer.join();
            return new Output(status, out, Bytes.of(err.toByteArray()).strip());
        } catch (IOException e) {
            throw new FetchException("cannot run " + cmd.get(0) + ": " + Bytes.fromJava(String.valueOf(e.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("interrupted while running " + cmd.get(0));
        }
    }

    private static String line(byte[] out) {
        return Bytes.of(out).strip();
    }

    /** NUL-separated output, as byte strings. */
    private static List<String> zsplit(byte[] out) {
        List<String> res = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < out.length; i++) {
            if (out[i] == 0) {
                res.add(Bytes.of(out, start, i - start));
                start = i + 1;
            }
        }
        if (start < out.length) res.add(Bytes.of(out, start, out.length - start));
        return res;
    }

    // ------------------------------------------------------------ queries

    /** The commit HEAD points to, or null (no commits yet). */
    String headRev() {
        Output o = git(List.of("rev-parse", "--verify", "--quiet", "HEAD^{commit}"), null, false);
        return o.status == 0 ? line(o.stdout) : null;
    }

    /** What HEAD refers to symbolically (e.g. {@code refs/heads/main}), or null if it's detached. */
    String workdirRef() {
        Output o = git(List.of("symbolic-ref", "--quiet", "HEAD"), null, false);
        return o.status == 0 ? line(o.stdout) : null;
    }

    /** {@code rev-parse ref^{commit}}. */
    String resolveRef(String ref) {
        Output o = git(List.of("rev-parse", "--verify", "--quiet", Bytes.toJava(ref) + "^{commit}"), null, false);
        if (o.status != 0) throw new FetchException("resolving Git reference '" + ref + "': not found");
        return line(o.stdout);
    }

    boolean hasObject(String rev) {
        return git(List.of("cat-file", "-e", rev + "^{commit}"), null, false).status == 0;
    }

    /** The commit time of {@code rev}. */
    long lastModified(String rev) {
        return Long.parseLong(line(git(List.of("log", "-1", "--format=%ct", rev), null, true).stdout));
    }

    boolean isShallow() {
        try {
            String dir = gitDir.equals(".") ? path : path + "/" + gitDir;
            return Fs.maybeLstat(dir + "/shallow") != null;
        } catch (IOException e) {
            return false;
        }
    }

    long revCount(String rev) {
        if (isShallow()) throw new FetchException("'" + path + "' is a shallow Git repository, so 'revCount' is not available");
        return Long.parseLong(line(git(List.of("rev-list", "--count", rev), null, true).stdout));
    }

    /** {@code git fetch --force [--depth 1] -- url refspec}. */
    void fetch(String url, String refspec, boolean shallow) {
        try {
            Fs.unlink(path + "/shallow.lock");
        } catch (IOException ignored) {
            // wasn't there
        }
        List<String> args = new ArrayList<>(List.of("fetch", "--quiet", "--force"));
        if (shallow) args.addAll(List.of("--depth", "1"));
        args.add("--");
        args.add(Bytes.toJava(url));
        args.add(Bytes.toJava(refspec));
        Output o = git(args, null, false);
        if (o.status != 0) throw new FetchException("Failed to fetch git repository '" + url + "'" + (o.stderr.isEmpty() ? "" : ": " + o.stderr));
    }

    /** {@code git ls-remote --symref url HEAD}: the branch (or commit) HEAD points to. */
    static String readRemoteHead(String url) {
        Output o = run(null, List.of("ls-remote", "--symref", Bytes.toJava(url), "HEAD"), null, false);
        if (o.status != 0) return null;
        for (String l : Bytes.of(o.stdout).split("\n")) {
            if (l.startsWith("ref: ") && l.endsWith("\tHEAD")) return l.substring(5, l.length() - 5);
        }
        for (String l : Bytes.of(o.stdout).split("\n")) {
            if (l.endsWith("\tHEAD")) return l.substring(0, l.indexOf('\t'));
        }
        return null;
    }

    // ------------------------------------------------------- working tree

    /** What the working tree has: tracked files that exist, and whether anything differs from HEAD. */
    record WorkdirInfo(String headRev, Set<String> files, Set<String> dirtyFiles, Set<String> deletedFiles,
                       boolean isDirty, List<String> submodules) {}

    WorkdirInfo workdirInfo() {
        String head = headRev();
        // Tracked files (the index), without submodules (gitlinks).
        Set<String> files = new TreeSet<>();
        Set<String> submodules = new TreeSet<>();
        for (String e : zsplit(git(List.of("ls-files", "-z", "--stage"), null, true).stdout)) {
            int tab = e.indexOf('\t');
            if (tab < 0) continue;
            String p = e.substring(tab + 1);
            if (e.startsWith("160000 ")) submodules.add(p); else files.add(p);
        }
        Set<String> dirty = new TreeSet<>();
        Set<String> deleted = new TreeSet<>();
        List<String> status = zsplit(git(List.of("status", "-z", "--porcelain=v1", "--untracked-files=no",
                "--ignore-submodules=all", "--no-renames"), null, true).stdout);
        for (String e : status) {
            if (e.length() < 4) continue;
            char x = e.charAt(0);
            char y = e.charAt(1);
            String p = e.substring(3);
            if (x == 'D' || y == 'D') {
                deleted.add(p);
                files.remove(p);
            } else {
                dirty.add(p);
                files.add(p);
            }
        }
        return new WorkdirInfo(head, files, dirty, deleted, !status.isEmpty(), new ArrayList<>(submodules));
    }

    /** Paths (relative, byte strings) that have the {@code export-ignore} attribute. */
    Set<String> exportIgnored(List<String> paths, String treeIsh) {
        Set<String> out = new HashSet<>();
        if (paths.isEmpty()) return out;
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        for (String p : paths) {
            in.writeBytes(Bytes.get(p));
            in.write(0);
        }
        List<String> args = new ArrayList<>(List.of("check-attr", "-z", "--stdin"));
        if (treeIsh != null) args.add("--source=" + treeIsh);
        args.add("export-ignore");
        List<String> res = zsplit(git(args, in.toByteArray(), true).stdout);
        for (int i = 0; i + 2 < res.size(); i += 3) {
            if (res.get(i + 2).equals("set")) out.add(res.get(i));
        }
        return out;
    }

    // --------------------------------------------------------- commit trees

    /**
     * Writes the tree of {@code rev} into {@code dest} (an existing, empty directory): blobs as
     * files (with the executable bit), symlinks, and submodules as empty directories. With {@code
     * exportIgnore}, paths with the {@code export-ignore} attribute are left out.
     */
    void exportTree(String rev, String dest, boolean exportIgnore) throws IOException {
        record Entry(String mode, String type, String oid, String path) {}
        List<Entry> entries = new ArrayList<>();
        for (String e : zsplit(git(List.of("ls-tree", "-r", "-t", "-z", "--full-tree", rev), null, true).stdout)) {
            int tab = e.indexOf('\t');
            String[] meta = e.substring(0, tab).split(" ");
            entries.add(new Entry(meta[0], meta[1], meta[2], e.substring(tab + 1)));
        }
        Set<String> ignored = new HashSet<>();
        if (exportIgnore) {
            List<String> paths = new ArrayList<>();
            for (Entry e : entries) paths.add(e.path);
            ignored = exportIgnored(paths, rev);
        }
        List<Entry> blobs = new ArrayList<>();
        Set<String> skipped = new HashSet<>();
        for (Entry e : entries) {
            int slash = e.path.lastIndexOf('/');
            String parent = slash < 0 ? "" : e.path.substring(0, slash);
            if (skipped.contains(parent) || ignored.contains(e.path)) {
                skipped.add(e.path);
                continue;
            }
            String target = dest + "/" + e.path;
            switch (e.type) {
                case "tree", "commit" -> Fs.mkdir(target, 0755);
                case "blob" -> blobs.add(e);
                default -> throw new IOException("file '" + e.path + "' has an unsupported Git file type");
            }
        }
        if (blobs.isEmpty()) return;
        // Stream the blobs out of `git cat-file --batch`.
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        for (Entry b : blobs) req.writeBytes((b.oid + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        List<String> args = List.of("-C", Bytes.toJava(path), "--git-dir", gitDir, "cat-file", "--batch");
        ProcessBuilder pb = new ProcessBuilder(concat(List.of("git"), args));
        Process p = pb.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Thread writer = new Thread(() -> {
            try (OutputStream o = p.getOutputStream()) {
                o.write(req.toByteArray());
            } catch (IOException e) {
                // reported by the reader
            }
        });
        writer.start();
        try (InputStream in = new java.io.BufferedInputStream(p.getInputStream(), 1 << 16)) {
            for (Entry b : blobs) {
                String header = readLine(in);
                String[] h = header.split(" ");
                if (h.length != 3 || !h[1].equals("blob")) throw new IOException("unexpected output from git cat-file: " + header);
                byte[] data = in.readNBytes(Integer.parseInt(h[2]));
                if (in.read() != '\n') throw new IOException("unexpected output from git cat-file");
                String target = dest + "/" + b.path;
                if (b.mode.equals("120000")) {
                    Fs.symlink(Bytes.of(data), target);
                } else {
                    Fs.writeFile(target, data, b.mode.equals("100755") ? 0755 : 0644);
                }
            }
        }
        try {
            writer.join();
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') sb.append((char) c);
        return sb.toString();
    }

    /** {@code .gitmodules} of a working tree: submodule path -> {url, branch}. */
    Map<String, Map<String, String>> parseGitmodules(String file) {
        Map<String, Map<String, String>> out = new HashMap<>();
        Output o = run(null, List.of("config", "--file", Bytes.toJava(file), "-z", "--get-regexp", "^submodule\\..*\\.(path|url|branch)$"), null, false);
        Map<String, Map<String, String>> byName = new HashMap<>();
        for (String e : zsplit(o.stdout)) {
            int nl = e.indexOf('\n');
            if (nl < 0) continue;
            String key = e.substring(0, nl);
            String value = e.substring(nl + 1);
            int last = key.lastIndexOf('.');
            String name = key.substring("submodule.".length(), last);
            byName.computeIfAbsent(name, k -> new HashMap<>()).put(key.substring(last + 1), value);
        }
        for (Map<String, String> m : byName.values()) if (m.containsKey("path")) out.put(m.get("path"), m);
        return out;
    }
}
