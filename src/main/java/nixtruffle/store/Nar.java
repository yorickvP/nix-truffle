package nixtruffle.store;

import com.oracle.truffle.api.TruffleFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Nix ARchive serialization of a file tree, used to hash (and upload) sources. */
public final class Nar {
    private Nar() {}

    /** A {@code builtins.path}/{@code filterSource} filter: called with the full path and its type. */
    public interface Filter {
        boolean include(String path, String type);
    }

    public static String typeOf(TruffleFile f) {
        if (f.isSymbolicLink()) return "symlink";
        if (f.isDirectory(LinkOption.NOFOLLOW_LINKS)) return "directory";
        if (f.isRegularFile(LinkOption.NOFOLLOW_LINKS)) return "regular";
        return "unknown";
    }

    public static void dump(TruffleFile root, Filter filter, OutputStream out) throws IOException {
        str(out, "nix-archive-1");
        node(root, filter, out);
    }

    public static Hash hash(TruffleFile root, Filter filter) throws IOException {
        MessageDigest md = Hash.digest("sha256");
        dump(root, filter, new OutputStream() {
            @Override
            public void write(int b) {
                md.update((byte) b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                md.update(b, off, len);
            }
        });
        return new Hash("sha256", md.digest());
    }

    private static void node(TruffleFile f, Filter filter, OutputStream out) throws IOException {
        str(out, "(");
        switch (typeOf(f)) {
            case "symlink" -> {
                str(out, "type");
                str(out, "symlink");
                str(out, "target");
                str(out, f.readSymbolicLink().toString());
            }
            case "regular" -> {
                str(out, "type");
                str(out, "regular");
                if (f.isExecutable()) {
                    str(out, "executable");
                    str(out, "");
                }
                str(out, "contents");
                bytes(out, f.readAllBytes());
            }
            case "directory" -> {
                str(out, "type");
                str(out, "directory");
                List<TruffleFile> children = new ArrayList<>(f.list());
                children.sort((a, b) -> compareBytes(a.getName(), b.getName()));
                for (TruffleFile child : children) {
                    if (filter != null && !filter.include(child.getPath(), typeOf(child))) continue;
                    str(out, "entry");
                    str(out, "(");
                    str(out, "name");
                    str(out, child.getName());
                    str(out, "node");
                    node(child, filter, out);
                    str(out, ")");
                }
            }
            default -> throw new IOException("file '" + f.getPath() + "' has an unsupported type");
        }
        str(out, ")");
    }

    static int compareBytes(String a, String b) {
        return java.util.Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static void str(OutputStream out, String s) throws IOException {
        bytes(out, s.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(OutputStream out, byte[] b) throws IOException {
        long len = b.length;
        byte[] header = new byte[8];
        for (int i = 0; i < 8; i++) header[i] = (byte) (len >>> (8 * i));
        out.write(header);
        out.write(b);
        int pad = (int) ((8 - len % 8) % 8);
        if (pad > 0) out.write(new byte[pad]);
    }
}
