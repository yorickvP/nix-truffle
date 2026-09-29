package nixtruffle.fs;

import nixtruffle.runtime.Bytes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * File system access with byte-exact paths. Paths and file names are Nix byte strings (see {@link
 * Bytes}), so names that aren't valid UTF-8, which {@link java.nio.file.Path} can't represent,
 * work. Calls libc through the FFM API (Linux: {@code statx} and {@code struct dirent} have the
 * same layout on every architecture).
 */
public final class Fs {
    private Fs() {}

    public static final int S_IFMT = 0170000;
    public static final int S_IFDIR = 0040000;
    public static final int S_IFREG = 0100000;
    public static final int S_IFLNK = 0120000;

    public static final int ENOENT = 2;
    public static final int EEXIST = 17;
    public static final int ENOTDIR = 20;

    /** What {@code lstat}/{@code stat} report. */
    public record Stat(int mode, long size, long mtime, long ino, long dev) {
        public int type() { return mode & S_IFMT; }
        public boolean isDirectory() { return type() == S_IFDIR; }
        public boolean isRegular() { return type() == S_IFREG; }
        public boolean isSymlink() { return type() == S_IFLNK; }
        public boolean isExecutable() { return (mode & 0100) != 0; }

        /** Nix's names for file types ({@code readFileType}, {@code builtins.path} filters). */
        public String typeName() {
            return switch (type()) {
                case S_IFDIR -> "directory";
                case S_IFREG -> "regular";
                case S_IFLNK -> "symlink";
                default -> "unknown";
            };
        }
    }

    /** A failed system call; {@link #errno} tells which error. */
    public static final class Error extends IOException {
        public final int errno;

        Error(String what, String path, int errno) {
            super(what + " '" + path + "': " + strerror(errno));
            this.errno = errno;
        }
    }

    // ----------------------------------------------------------------- libc

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();
    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    private static final Linker.Option ERRNO_OPT = Linker.Option.captureCallState("errno");

    private static MethodHandle fn(String name, FunctionDescriptor fd, Linker.Option... options) {
        return LINKER.downcallHandle(LIBC.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), fd, options);
    }

    private static final int AT_FDCWD = -100;
    private static final int AT_SYMLINK_NOFOLLOW = 0x100;
    private static final int STATX_BASIC_STATS = 0x7ff;
    private static final int O_RDONLY = 0;
    private static final int O_WRONLY = 1;
    private static final int O_CREAT = 0100;
    private static final int O_EXCL = 0200;
    private static final int O_TRUNC = 01000;
    private static final int O_CLOEXEC = 02000000;

    private static final MethodHandle STATX = fn("statx", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), ERRNO_OPT);
    private static final MethodHandle OPENDIR = fn("opendir", FunctionDescriptor.of(ADDRESS, ADDRESS), ERRNO_OPT);
    private static final MethodHandle READDIR = fn("readdir", FunctionDescriptor.of(ADDRESS, ADDRESS), ERRNO_OPT);
    private static final MethodHandle CLOSEDIR = fn("closedir", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle READLINK = fn("readlink", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG), ERRNO_OPT);
    private static final MethodHandle OPEN = fn("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), ERRNO_OPT, Linker.Option.firstVariadicArg(2));
    private static final MethodHandle READ = fn("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), ERRNO_OPT);
    private static final MethodHandle WRITE = fn("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), ERRNO_OPT);
    private static final MethodHandle CLOSE = fn("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle MKDIR = fn("mkdir", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), ERRNO_OPT);
    private static final MethodHandle SYMLINK = fn("symlink", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), ERRNO_OPT);
    private static final MethodHandle CHMOD = fn("chmod", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), ERRNO_OPT);
    private static final MethodHandle UNLINK = fn("unlink", FunctionDescriptor.of(JAVA_INT, ADDRESS), ERRNO_OPT);
    private static final MethodHandle RMDIR = fn("rmdir", FunctionDescriptor.of(JAVA_INT, ADDRESS), ERRNO_OPT);
    private static final MethodHandle RENAME = fn("rename", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), ERRNO_OPT);
    private static final MethodHandle REALPATH = fn("realpath", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS), ERRNO_OPT);
    private static final MethodHandle FREE = fn("free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle STRERROR = fn("strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle ERRNO_LOCATION = fn("__errno_location", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle UTIMENSAT = fn("utimensat", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), ERRNO_OPT);

    private static MemorySegment cstr(Arena arena, String path) {
        byte[] b = Bytes.get(path);
        MemorySegment s = arena.allocate(b.length + 1);
        MemorySegment.copy(b, 0, s, ValueLayout.JAVA_BYTE, 0, b.length);
        s.set(ValueLayout.JAVA_BYTE, b.length, (byte) 0);
        return s;
    }

    private static String readCstr(MemorySegment s, long max) {
        MemorySegment r = s.reinterpret(max);
        long n = 0;
        while (n < max && r.get(ValueLayout.JAVA_BYTE, n) != 0) n++;
        byte[] b = new byte[(int) n];
        MemorySegment.copy(r, ValueLayout.JAVA_BYTE, 0, b, 0, (int) n);
        return Bytes.of(b);
    }

    private static int errno(MemorySegment state) {
        return (int) ERRNO.get(state, 0L);
    }

    static String strerror(int errno) {
        try {
            return Bytes.toJava(readCstr((MemorySegment) STRERROR.invokeExact(errno), 256));
        } catch (Throwable t) {
            return "errno " + errno;
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof java.lang.Error e) throw e;
        return new IllegalStateException(t);
    }

    // ------------------------------------------------------------ queries

    private static Stat statx(String path, boolean follow) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            MemorySegment buf = arena.allocate(256, 8);
            int r = (int) STATX.invokeExact(state, AT_FDCWD, cstr(arena, path), follow ? 0 : AT_SYMLINK_NOFOLLOW, STATX_BASIC_STATS, buf);
            if (r != 0) throw new Error("getting status of", path, errno(state));
            int mode = Short.toUnsignedInt(buf.get(ValueLayout.JAVA_SHORT, 28));
            long ino = buf.get(ValueLayout.JAVA_LONG, 32);
            long size = buf.get(ValueLayout.JAVA_LONG, 40);
            long mtime = buf.get(ValueLayout.JAVA_LONG, 112);
            long dev = ((long) buf.get(JAVA_INT, 136) << 32) | Integer.toUnsignedLong(buf.get(JAVA_INT, 140));
            return new Stat(mode, size, mtime, ino, dev);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code lstat}: symlinks are not followed. */
    public static Stat lstat(String path) throws IOException {
        return statx(path, false);
    }

    /** {@code stat}: follows symlinks. */
    public static Stat stat(String path) throws IOException {
        return statx(path, true);
    }

    /** {@code lstat}, or null if the path doesn't exist. */
    public static Stat maybeLstat(String path) throws IOException {
        try {
            return lstat(path);
        } catch (Error e) {
            if (e.errno == ENOENT || e.errno == ENOTDIR) return null;
            throw e;
        }
    }

    /** {@code stat}, or null if the path (or a symlink's target) doesn't exist. */
    public static Stat maybeStat(String path) throws IOException {
        try {
            return stat(path);
        } catch (Error e) {
            if (e.errno == ENOENT || e.errno == ENOTDIR) return null;
            throw e;
        }
    }

    /** A directory entry: its name and its type as {@code readdir} reports it ({@code DT_*}). */
    public record Entry(String name, int dtype) {
        public static final int DT_UNKNOWN = 0;
        public static final int DT_DIR = 4;
        public static final int DT_REG = 8;
        public static final int DT_LNK = 10;
    }

    /** The entries of a directory (without {@code .} and {@code ..}), unsorted. */
    public static List<Entry> readDirectory(String path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            MemorySegment dir = (MemorySegment) OPENDIR.invokeExact(state, cstr(arena, path));
            if (dir.address() == 0) throw new Error("opening directory", path, errno(state));
            List<Entry> out = new ArrayList<>();
            try {
                MemorySegment errnoLocation = ((MemorySegment) ERRNO_LOCATION.invokeExact()).reinterpret(4);
                while (true) {
                    // readdir leaves errno alone at the end of the directory and sets it on
                    // errors, so it has to be cleared first.
                    errnoLocation.set(JAVA_INT, 0, 0);
                    MemorySegment ent = (MemorySegment) READDIR.invokeExact(state, dir);
                    if (ent.address() == 0) {
                        int e = errno(state);
                        if (e != 0) throw new Error("reading directory", path, e);
                        break;
                    }
                    MemorySegment d = ent.reinterpret(19 + 256);
                    int dtype = Byte.toUnsignedInt(d.get(ValueLayout.JAVA_BYTE, 18));
                    String name = readCstr(d.asSlice(19), 256);
                    if (name.equals(".") || name.equals("..")) continue;
                    out.add(new Entry(name, dtype));
                }
            } finally {
                int ignored = (int) CLOSEDIR.invokeExact(dir);
            }
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Entry names of a directory, sorted bytewise (like Nix and NAR serialisation). */
    public static List<String> list(String path) throws IOException {
        List<String> names = new ArrayList<>();
        for (Entry e : readDirectory(path)) names.add(e.name());
        names.sort(null);
        return names;
    }

    public static String readLink(String path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            MemorySegment p = cstr(arena, path);
            for (long size = 256; ; size *= 4) {
                MemorySegment buf = arena.allocate(size);
                long n = (long) READLINK.invokeExact(state, p, buf, size);
                if (n < 0) throw new Error("reading symbolic link", path, errno(state));
                if (n < size) {
                    byte[] b = new byte[(int) n];
                    MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, b, 0, (int) n);
                    return Bytes.of(b);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code realpath}: absolute, with all symlinks resolved. */
    public static String realPath(String path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            MemorySegment r = (MemorySegment) REALPATH.invokeExact(state, cstr(arena, path), MemorySegment.NULL);
            if (r.address() == 0) throw new Error("resolving", path, errno(state));
            try {
                return readCstr(r, 1 << 16);
            } finally {
                FREE.invokeExact(r);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // --------------------------------------------------------------- files

    private static int open(Arena arena, String path, int flags, int mode) throws IOException {
        try {
            MemorySegment state = arena.allocate(CAPTURE);
            int fd = (int) OPEN.invokeExact(state, cstr(arena, path), flags | O_CLOEXEC, mode);
            if (fd < 0) throw new Error("opening file", path, errno(state));
            return fd;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static void close(int fd) {
        try {
            int ignored = (int) CLOSE.invokeExact(fd);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Streams a file's contents (following symlinks). */
    public static void readFile(String path, OutputStream out) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            int fd = open(arena, path, O_RDONLY, 0);
            try {
                MemorySegment state = arena.allocate(CAPTURE);
                int chunk = 1 << 16;
                MemorySegment buf = arena.allocate(chunk);
                byte[] bytes = new byte[chunk];
                while (true) {
                    long n = (long) READ.invokeExact(state, fd, buf, (long) chunk);
                    if (n < 0) {
                        int e = errno(state);
                        if (e == 4) continue; // EINTR
                        throw new Error("reading file", path, e);
                    }
                    if (n == 0) break;
                    MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, bytes, 0, (int) n);
                    out.write(bytes, 0, (int) n);
                }
            } finally {
                close(fd);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static byte[] readFile(String path) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        readFile(path, out);
        return out.toByteArray();
    }

    /** Creates (or truncates) a file with these contents and permissions. */
    public static void writeFile(String path, byte[] data, int mode) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            int fd = open(arena, path, O_WRONLY | O_CREAT | O_TRUNC, mode);
            try {
                MemorySegment state = arena.allocate(CAPTURE);
                MemorySegment buf = arena.allocate(Math.max(1, data.length));
                MemorySegment.copy(data, 0, buf, ValueLayout.JAVA_BYTE, 0, data.length);
                long off = 0;
                while (off < data.length) {
                    long n = (long) WRITE.invokeExact(state, fd, buf.asSlice(off), (long) data.length - off);
                    if (n < 0) {
                        int e = errno(state);
                        if (e == 4) continue;
                        throw new Error("writing file", path, e);
                    }
                    off += n;
                }
            } finally {
                close(fd);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
        chmod(path, mode);
    }

    private static void call(MethodHandle h, String what, String path, int mode) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            int r = mode < 0 ? (int) h.invokeExact(state, cstr(arena, path)) : (int) h.invokeExact(state, cstr(arena, path), mode);
            if (r != 0) throw new Error(what, path, errno(state));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void mkdir(String path, int mode) throws IOException {
        call(MKDIR, "creating directory", path, mode);
    }

    /** {@code mkdir -p}. */
    public static void mkdirs(String path) throws IOException {
        if (maybeStat(path) != null) return;
        int slash = path.lastIndexOf('/');
        if (slash > 0) mkdirs(path.substring(0, slash));
        try {
            mkdir(path, 0777);
        } catch (Error e) {
            if (e.errno != EEXIST) throw e;
        }
    }

    public static void chmod(String path, int mode) throws IOException {
        call(CHMOD, "changing permissions of", path, mode);
    }

    public static void unlink(String path) throws IOException {
        call(UNLINK, "deleting", path, -1);
    }

    public static void rmdir(String path) throws IOException {
        call(RMDIR, "deleting directory", path, -1);
    }

    public static void symlink(String target, String path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            int r = (int) SYMLINK.invokeExact(state, cstr(arena, target), cstr(arena, path));
            if (r != 0) throw new Error("creating symlink", path, errno(state));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void rename(String from, String to) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            int r = (int) RENAME.invokeExact(state, cstr(arena, from), cstr(arena, to));
            if (r != 0) throw new Error("renaming", from, errno(state));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Sets a file's access and modification times to now. */
    public static void touch(String path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            int r = (int) UTIMENSAT.invokeExact(state, AT_FDCWD, cstr(arena, path), MemorySegment.NULL, 0);
            if (r != 0) throw new Error("setting the modification time of", path, errno(state));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code rm -rf}. */
    public static void deleteTree(String path) throws IOException {
        Stat st = maybeLstat(path);
        if (st == null) return;
        if (st.isDirectory()) {
            // Make it writable first: read-only directories (like store paths) can't be emptied.
            if ((st.mode() & 0200) == 0) chmod(path, (st.mode() & 07777) | 0700);
            for (Entry e : readDirectory(path)) deleteTree(path + "/" + e.name());
            rmdir(path);
        } else {
            unlink(path);
        }
    }
}
