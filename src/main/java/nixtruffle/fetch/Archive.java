package nixtruffle.fetch;

import nixtruffle.fs.Fs;
import nixtruffle.runtime.Bytes;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unpacks archives into a directory, like CppNix's {@code unpackTarfileToSink} (via libarchive):
 * tar (plain, gzip, bzip2, xz or zstd, detected by content) and zip. Entry paths are
 * canonicalised ({@code ./a} is {@code a}), names stay byte-exact, missing parent directories
 * are created, hard links become copies, and the newest entry mtime is returned (the tarball's
 * {@code lastModified}).
 */
public final class Archive {
    private Archive() {}

    /** Unpacks the archive in {@code file} into the (existing, empty) directory {@code dest}. */
    public static long unpack(String file, String dest) throws IOException {
        byte[] magic = new byte[6];
        try (InputStream in = new FsInput(file)) {
            int n = in.readNBytes(magic, 0, magic.length);
            if (n >= 4 && magic[0] == 'P' && magic[1] == 'K' && (magic[2] == 3 || magic[2] == 5)) return unpackZip(file, dest);
        }
        try (InputStream raw = new BufferedInputStream(new FsInput(file), 1 << 16)) {
            return unpackTar(decompress(raw), dest);
        }
    }

    /** Wraps {@code in} in a decompressor for its format (none if it isn't compressed). */
    static InputStream decompress(InputStream in) throws IOException {
        in.mark(8);
        byte[] m = in.readNBytes(6);
        in.reset();
        if (m.length >= 2 && (m[0] & 0xff) == 0x1f && (m[1] & 0xff) == 0x8b) return new java.util.zip.GZIPInputStream(in, 1 << 16);
        if (m.length >= 3 && m[0] == 'B' && m[1] == 'Z' && m[2] == 'h') return new BZip2CompressorInputStream(in, true);
        if (m.length >= 6 && (m[0] & 0xff) == 0xfd && m[1] == '7' && m[2] == 'z' && m[3] == 'X' && m[4] == 'Z' && m[5] == 0) {
            return new org.tukaani.xz.XZInputStream(in);
        }
        if (m.length >= 4 && (m[0] & 0xff) == 0x28 && (m[1] & 0xff) == 0xb5 && (m[2] & 0xff) == 0x2f && (m[3] & 0xff) == 0xfd) {
            return new io.airlift.compress.zstd.ZstdInputStream(in);
        }
        return in;
    }

    /** {@code CanonPath}: components without empty ones, "." and ".." (which pops). */
    static List<String> canon(String path) {
        List<String> out = new ArrayList<>();
        for (String c : path.split("/")) {
            if (c.isEmpty() || c.equals(".")) continue;
            if (c.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else {
                out.add(c);
            }
        }
        return out;
    }

    // ---------------------------------------------------------- the sink

    /** Creates files under a root, making parent directories as needed. */
    private static final class Sink {
        final String root;
        final Map<String, Boolean> dirs = new HashMap<>();

        Sink(String root) {
            this.root = root;
        }

        String path(List<String> c) {
            return c.isEmpty() ? root : root + "/" + String.join("/", c);
        }

        void parents(List<String> c) throws IOException {
            for (int i = 1; i < c.size(); i++) dir(c.subList(0, i));
        }

        void dir(List<String> c) throws IOException {
            String p = path(c);
            if (dirs.containsKey(p)) return;
            parents(c);
            Fs.Stat st = Fs.maybeLstat(p);
            if (st != null && !st.isDirectory()) {
                Fs.deleteTree(p);
                st = null;
            }
            if (st == null) Fs.mkdir(p, 0755);
            dirs.put(p, true);
        }

        void replace(List<String> c) throws IOException {
            parents(c);
            String p = path(c);
            if (Fs.maybeLstat(p) != null) {
                Fs.deleteTree(p);
                dirs.remove(p);
            }
        }

        void file(List<String> c, byte[] data, boolean executable) throws IOException {
            if (c.isEmpty()) throw new IOException("archive has a file as its root");
            replace(c);
            Fs.writeFile(path(c), data, executable ? 0755 : 0644);
        }

        void symlink(List<String> c, String target) throws IOException {
            if (c.isEmpty()) throw new IOException("archive has a symlink as its root");
            replace(c);
            Fs.symlink(target, path(c));
        }

        void hardlink(List<String> c, List<String> target) throws IOException {
            String t = path(target);
            Fs.Stat st = Fs.maybeLstat(t);
            if (st == null) throw new IOException("hard link target '" + String.join("/", target) + "' does not exist");
            if (st.isSymlink()) {
                symlink(c, Fs.readLink(t));
            } else {
                file(c, Fs.readFile(t), st.isExecutable());
            }
        }
    }

    // ----------------------------------------------------------------- tar

    private static long unpackTar(InputStream in, String dest) throws IOException {
        Sink sink = new Sink(dest);
        long lastModified = 0;
        String longName = null;
        String longLink = null;
        Map<String, String> pax = new HashMap<>();
        Map<String, String> globalPax = new HashMap<>();
        byte[] header = new byte[512];
        int zeroBlocks = 0;
        while (true) {
            int n = in.readNBytes(header, 0, 512);
            if (n == 0) break;
            if (n < 512) throw new IOException("truncated tar archive");
            if (isZero(header)) {
                if (++zeroBlocks == 2) break;
                continue;
            }
            zeroBlocks = 0;
            char type = (char) header[156];
            long size = number(header, 124, 12);
            byte[] data = null;
            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                data = readData(in, size);
                String s = Bytes.of(data);
                switch (type) {
                    case 'L' -> longName = cstr(s);
                    case 'K' -> longLink = cstr(s);
                    case 'x' -> pax.putAll(parsePax(data));
                    default -> globalPax.putAll(parsePax(data));
                }
                continue;
            }
            Map<String, String> p = new HashMap<>(globalPax);
            p.putAll(pax);
            String name = field(header, 0, 100);
            String magic = field(header, 257, 6);
            // POSIX ustar headers have a name prefix; GNU ones ("ustar  ") use that space otherwise.
            if (magic.equals("ustar")) {
                String prefix = field(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
            }
            if (longName != null) name = longName;
            if (p.containsKey("path")) name = p.get("path");
            String link = field(header, 157, 100);
            if (longLink != null) link = longLink;
            if (p.containsKey("linkpath")) link = p.get("linkpath");
            if (p.containsKey("size")) size = Long.parseLong(p.get("size"));
            long mtime = number(header, 136, 12);
            if (p.containsKey("mtime")) mtime = (long) Math.floor(Double.parseDouble(p.get("mtime")));
            long mode = number(header, 100, 8);
            longName = null;
            longLink = null;
            pax.clear();

            lastModified = Math.max(lastModified, mtime);
            List<String> c = canon(name);
            switch (type) {
                case '0', '\0', '7' -> sink.file(c, readData(in, size), (mode & 0100) != 0);
                case '1' -> {
                    skip(in, size);
                    sink.hardlink(c, canon(link));
                }
                case '2' -> {
                    skip(in, size);
                    sink.symlink(c, link);
                }
                case '5' -> {
                    skip(in, size);
                    sink.dir(c);
                }
                default -> throw new IOException("file '" + name + "' in tarball has unsupported file type " + (int) type);
            }
        }
        return lastModified;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    /** A NUL-terminated field, as a byte string. */
    private static String field(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        return Bytes.of(h, off, end - off);
    }

    private static String cstr(String s) {
        int nul = s.indexOf('\0');
        return nul < 0 ? s : s.substring(0, nul);
    }

    /** Octal (possibly space/NUL padded) or GNU base-256 numbers. */
    private static long number(byte[] h, int off, int len) {
        if ((h[off] & 0x80) != 0) {
            long v = h[off] & 0x3f;
            for (int i = 1; i < len; i++) v = (v << 8) | (h[off + i] & 0xff);
            return v;
        }
        long v = 0;
        int i = off;
        while (i < off + len && (h[i] == ' ' || h[i] == 0)) i++;
        for (; i < off + len && h[i] >= '0' && h[i] <= '7'; i++) v = v * 8 + (h[i] - '0');
        return v;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > Integer.MAX_VALUE - 16) throw new IOException("tar entry too large");
        byte[] data = in.readNBytes((int) size);
        if (data.length != size) throw new IOException("truncated tar archive");
        skip(in, (512 - size % 512) % 512);
        return data;
    }

    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("truncated tar archive");
                s = 1;
            }
            n -= s;
        }
    }

    /** PAX extended header records: {@code "<len> <key>=<value>\n"}; values are UTF-8, kept as bytes. */
    private static Map<String, String> parsePax(byte[] data) {
        Map<String, String> out = new HashMap<>();
        int pos = 0;
        while (pos < data.length) {
            int sp = pos;
            while (sp < data.length && data[sp] != ' ') sp++;
            if (sp >= data.length) break;
            int len;
            try {
                len = Integer.parseInt(new String(data, pos, sp - pos, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || pos + len > data.length) break;
            String record = Bytes.of(data, sp + 1, pos + len - sp - 2);
            int eq = record.indexOf('=');
            if (eq > 0) out.put(record.substring(0, eq), record.substring(eq + 1));
            pos += len;
        }
        return out;
    }

    // ----------------------------------------------------------------- zip

    private static long unpackZip(String file, String dest) throws IOException {
        Sink sink = new Sink(dest);
        long lastModified = 0;
        try (ZipFile zip = ZipFile.builder().setPath(java.nio.file.Path.of(Bytes.toJava(file))).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry e = entries.nextElement();
                String name = Bytes.of(e.getRawName());
                lastModified = Math.max(lastModified, e.getTime() / 1000);
                List<String> c = canon(name);
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                if (!e.isDirectory()) {
                    try (InputStream in = zip.getInputStream(e)) {
                        in.transferTo(data);
                    }
                }
                if (e.isDirectory()) {
                    sink.dir(c);
                } else if (e.isUnixSymlink()) {
                    sink.symlink(c, Bytes.of(data.toByteArray()));
                } else {
                    int mode = e.getUnixMode();
                    sink.file(c, data.toByteArray(), (mode & 0100) != 0);
                }
            }
        }
        return lastModified;
    }

    /** An input stream over a file with a byte-string path. */
    private static final class FsInput extends InputStream {
        private final InputStream in;

        FsInput(String path) throws IOException {
            // Files we unpack are temporary downloads with ASCII names, or local files.
            java.nio.file.Path p = java.nio.file.Path.of(Bytes.toJava(path));
            this.in = java.nio.file.Files.newInputStream(p);
        }

        @Override
        public int read() throws IOException {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return in.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
