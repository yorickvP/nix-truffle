package nixtruffle.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.Collection;

/**
 * A minimal client for the Nix daemon's worker protocol (1.35): just enough to check path
 * validity and add content-addressed paths (text files, .drv files, NAR-serialized sources).
 */
public final class DaemonClient implements AutoCloseable {
    private static final long WORKER_MAGIC_1 = 0x6e697863L;
    private static final long WORKER_MAGIC_2 = 0x6478696fL;
    private static final long PROTOCOL_VERSION = (1 << 8) | 35;

    private static final long STDERR_NEXT = 0x6f6c6d67L;
    private static final long STDERR_READ = 0x64617461L;
    private static final long STDERR_WRITE = 0x64617416L;
    private static final long STDERR_LAST = 0x616c7473L;
    private static final long STDERR_ERROR = 0x63787470L;
    private static final long STDERR_START_ACTIVITY = 0x53545254L;
    private static final long STDERR_STOP_ACTIVITY = 0x53544f50L;
    private static final long STDERR_RESULT = 0x52534c54L;

    private static final long OP_IS_VALID_PATH = 1;
    private static final long OP_ADD_TO_STORE = 7;
    private static final long OP_ADD_TEMP_ROOT = 11;
    private static final long OP_QUERY_PATH_INFO = 26;

    private final SocketChannel channel;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    /** Set when an operation failed half-way: the connection is out of sync and must be dropped. */
    private boolean broken;

    public boolean isBroken() { return broken; }

    public DaemonClient() throws IOException {
        String socket = nixtruffle.util.Proc.env().getOrDefault("NIX_DAEMON_SOCKET_PATH", "/nix/var/nix/daemon-socket/socket");
        channel = SocketChannel.open(UnixDomainSocketAddress.of(socket));
        writeU64(WORKER_MAGIC_1);
        flush();
        if (readU64() != WORKER_MAGIC_2) throw new IOException("protocol mismatch with the nix daemon");
        long serverVersion = readU64();
        if ((serverVersion >> 8) != 1 || (serverVersion & 0xff) < 35) throw new IOException("nix daemon protocol too old");
        writeU64(PROTOCOL_VERSION);
        writeU64(0); // cpu affinity
        writeU64(0); // reserve space
        flush();
        readString(); // daemon version
        readU64(); // trusted status
        processStderr();
    }

    public boolean isValidPath(String path) throws IOException {
        writeU64(OP_IS_VALID_PATH);
        writeString(path);
        flush();
        processStderr();
        return readU64() != 0;
    }

    /** What the daemon knows about a valid store path. */
    public record PathInfo(String narHash, java.util.List<String> references, long narSize, String ca) {}

    /** {@code wopQueryPathInfo}: null if the path isn't valid. */
    public PathInfo queryPathInfo(String path) throws IOException {
        writeU64(OP_QUERY_PATH_INFO);
        writeString(path);
        flush();
        processStderr();
        if (readU64() == 0) return null;
        readString(); // deriver
        String narHash = readString();
        long n = readU64();
        java.util.List<String> refs = new java.util.ArrayList<>();
        for (long i = 0; i < n; i++) refs.add(readString());
        readU64(); // registration time
        long narSize = readU64();
        readU64(); // ultimate
        long sigs = readU64();
        for (long i = 0; i < sigs; i++) readString();
        String ca = readString();
        return new PathInfo(narHash, refs, narSize, ca);
    }

    /** {@code wopAddTempRoot}: protects a path from garbage collection while we're connected. */
    public void addTempRoot(String path) throws IOException {
        writeU64(OP_ADD_TEMP_ROOT);
        writeString(path);
        flush();
        processStderr();
        readU64();
    }

    /** Streams data to the daemon in frames. */
    public interface Dump {
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * Adds a content-addressed path; {@code method} is {@code text:sha256}, {@code fixed:r:sha256}
     * or {@code fixed:sha256}. Returns the store path the daemon computed.
     */
    public String addToStore(String name, String method, Collection<String> references, Dump dump) throws IOException {
        writeU64(OP_ADD_TO_STORE);
        writeString(name);
        writeString(method);
        writeU64(references.size());
        for (String r : references) writeString(r);
        writeU64(0); // repair
        flush();
        OutputStream framed = new OutputStream() {
            private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

            @Override
            public void write(int b) throws IOException {
                buf.write(b);
                if (buf.size() >= 65536) frame();
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                buf.write(b, off, len);
                if (buf.size() >= 65536) frame();
            }

            private void frame() throws IOException {
                writeU64(buf.size());
                out.write(buf.toByteArray());
                buf.reset();
                DaemonClient.this.flush();
            }

            @Override
            public void close() throws IOException {
                if (buf.size() > 0) frame();
            }
        };
        try {
            dump.writeTo(framed);
            framed.close();
        } catch (IOException | RuntimeException e) {
            // The daemon is still waiting for the rest of the data.
            broken = true;
            channel.close();
            throw e;
        }
        writeU64(0);
        flush();
        processStderr();
        String path = readString();
        readString(); // deriver
        readString(); // nar hash
        long refs = readU64();
        for (long i = 0; i < refs; i++) readString();
        readU64(); // registration time
        readU64(); // nar size
        readU64(); // ultimate
        long sigs = readU64();
        for (long i = 0; i < sigs; i++) readString();
        readString(); // ca
        return path;
    }

    private void processStderr() throws IOException {
        while (true) {
            long msg = readU64();
            if (msg == STDERR_LAST) return;
            if (msg == STDERR_NEXT || msg == STDERR_WRITE) {
                readString();
            } else if (msg == STDERR_ERROR) {
                readString(); // type
                readU64(); // level
                readString(); // name
                String message = readString();
                readU64(); // have pos
                long traces = readU64();
                for (long i = 0; i < traces; i++) {
                    readU64();
                    readString();
                }
                throw new IOException("nix daemon: " + message);
            } else if (msg == STDERR_START_ACTIVITY) {
                readU64();
                readU64();
                readU64();
                readString();
                readFields();
                readU64();
            } else if (msg == STDERR_STOP_ACTIVITY) {
                readU64();
            } else if (msg == STDERR_RESULT) {
                readU64();
                readU64();
                readFields();
            } else if (msg == STDERR_READ) {
                throw new IOException("nix daemon asked for data unexpectedly");
            } else {
                throw new IOException("unknown message from nix daemon: 0x" + Long.toHexString(msg));
            }
        }
    }

    private void readFields() throws IOException {
        long n = readU64();
        for (long i = 0; i < n; i++) {
            if (readU64() == 0) readU64(); else readString();
        }
    }

    // ------------------------------------------------------------ wire format

    private void writeU64(long v) {
        for (int i = 0; i < 8; i++) out.write((int) (v >>> (8 * i)));
    }

    private void writeString(String s) {
        byte[] b = nixtruffle.runtime.Bytes.get(s);
        writeU64(b.length);
        out.writeBytes(b);
        for (int i = b.length; i % 8 != 0; i++) out.write(0);
    }

    private void flush() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(out.toByteArray());
        while (buf.hasRemaining()) channel.write(buf);
        out.reset();
    }

    private ByteBuffer read(int n) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) throw new IOException("nix daemon closed the connection");
        }
        return buf.flip();
    }

    private long readU64() throws IOException {
        return read(8).getLong();
    }

    private String readString() throws IOException {
        int n = (int) readU64();
        ByteBuffer buf = read(n + (8 - n % 8) % 8);
        return nixtruffle.runtime.Bytes.of(buf.array(), 0, n);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
