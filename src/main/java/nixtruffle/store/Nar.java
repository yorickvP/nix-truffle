package nixtruffle.store;

import nixtruffle.fs.Fs;
import nixtruffle.runtime.Bytes;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.List;

/** Nix ARchive serialization of a file tree, used to hash (and upload) sources. */
public final class Nar {
    private Nar() {}

    /** A {@code builtins.path}/{@code filterSource} filter: called with the full path and its type. */
    public interface Filter {
        boolean include(String path, String type);
    }

    /** Serializes the file tree at {@code path} (a byte string); symlinks are not followed. */
    public static void dump(String path, Filter filter, OutputStream out) throws IOException {
        str(out, "nix-archive-1");
        node(path, Fs.lstat(path), filter, out);
    }

    public static Hash hash(String path, Filter filter) throws IOException {
        HashSink sink = new HashSink("sha256");
        dump(path, filter, sink);
        return sink.finish();
    }

    private static void node(String path, Fs.Stat st, Filter filter, OutputStream out) throws IOException {
        str(out, "(");
        str(out, "type");
        if (st.isSymlink()) {
            str(out, "symlink");
            str(out, "target");
            str(out, Fs.readLink(path));
        } else if (st.isRegular()) {
            str(out, "regular");
            if (st.isExecutable()) {
                str(out, "executable");
                str(out, "");
            }
            str(out, "contents");
            contents(path, st.size(), out);
        } else if (st.isDirectory()) {
            str(out, "directory");
            List<String> names = Fs.list(path);
            String prefix = path.endsWith("/") ? path : path + "/";
            for (String name : names) {
                String child = prefix + name;
                Fs.Stat cst = Fs.lstat(child);
                if (filter != null && !filter.include(child, cst.typeName())) continue;
                str(out, "entry");
                str(out, "(");
                str(out, "name");
                str(out, name);
                str(out, "node");
                node(child, cst, filter, out);
                str(out, ")");
            }
        } else {
            throw new IOException("file '" + path + "' has an unsupported type");
        }
        str(out, ")");
    }

    private static void contents(String path, long size, OutputStream out) throws IOException {
        long[] n = {0};
        header(out, size);
        Fs.readFile(path, new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                if (++n[0] <= size) out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                int keep = (int) Math.max(0, Math.min(len, size - n[0]));
                if (keep > 0) out.write(b, off, keep);
                n[0] += len;
            }
        });
        if (n[0] != size) throw new IOException("file '" + path + "' changed while it was being read");
        pad(out, size);
    }

    /** A NAR string: length, bytes, zero padding to a multiple of 8. */
    public static void str(OutputStream out, String s) throws IOException {
        bytes(out, Bytes.get(s));
    }

    public static void bytes(OutputStream out, byte[] b) throws IOException {
        header(out, b.length);
        out.write(b);
        pad(out, b.length);
    }

    static void header(OutputStream out, long len) throws IOException {
        byte[] header = new byte[8];
        for (int i = 0; i < 8; i++) header[i] = (byte) (len >>> (8 * i));
        out.write(header);
    }

    static void pad(OutputStream out, long len) throws IOException {
        int pad = (int) ((8 - len % 8) % 8);
        if (pad > 0) out.write(new byte[pad]);
    }

    /** An output stream that hashes what is written to it. */
    public static final class HashSink extends OutputStream {
        private final String algo;
        private final MessageDigest md;
        private long size;

        public HashSink(String algo) {
            this.algo = algo;
            md = Hash.digest(algo);
        }

        @Override
        public void write(int b) {
            md.update((byte) b);
            size++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            md.update(b, off, len);
            size += len;
        }

        public long size() { return size; }

        public Hash finish() {
            return new Hash(algo, md.digest());
        }
    }
}
