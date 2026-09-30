package nixtruffle.launcher;

import nixtruffle.util.Proc;
import org.graalvm.polyglot.Engine;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * {@code nix-truffle} as a daemon (see {@link Client}): it runs commands like the launcher does,
 * each in a context of its own, but the contexts share one engine: files are parsed once (and
 * again when they change) and compiled code stays warm, so repeated evaluations are fast. It
 * exits when its build changes, when stopped, or after being idle for {@code
 * NIX_TRUFFLE_DAEMON_IDLE} seconds (default: three hours).
 */
public final class Daemon {
    private Daemon() {}

    /** The engine the commands' contexts share; null outside the daemon. */
    static volatile Engine engine;

    /** Thrown by commands that must run in the client's process (they need its terminal). */
    static final class RunLocally extends RuntimeException {
        RunLocally() {
            super(null, null, false, false);
        }
    }

    private static final long STARTED = System.currentTimeMillis();
    private static final AtomicInteger active = new AtomicInteger();
    private static final AtomicInteger served = new AtomicInteger();
    private static volatile long lastActive = System.nanoTime();
    private static volatile boolean retiring;
    private static ServerSocketChannel server;
    private static Path socket;
    private static FileChannel lockFile;
    private static long build;

    public static void main(String[] args) throws Exception {
        socket = Path.of(args[0]);
        // One daemon per socket: the one that holds the lock.
        lockFile = FileChannel.open(Path.of(args[0] + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = lockFile.tryLock();
        if (lock == null) return;
        // Don't take the signals meant for the terminal of the client that started us.
        for (String sig : new String[] {"INT", "HUP"}) {
            try {
                sun.misc.Signal.handle(new sun.misc.Signal(sig), s -> {});
            } catch (IllegalArgumentException e) {
                // not a signal here
            }
        }
        build = fingerprint();
        // Standard streams are the current client's (see Proc.Client), or the log.
        PrintStream log = System.out;
        System.setOut(new PrintStream(new Dispatch(c -> c.out, log), true));
        System.setErr(new PrintStream(new Dispatch(c -> c.err, log), true));
        System.setIn(new InputStream() {
            @Override
            public int read() throws IOException {
                Proc.Client c = Proc.client();
                return c == null ? -1 : c.in.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                Proc.Client c = Proc.client();
                return c == null ? -1 : c.in.read(b, off, len);
            }
        });
        engine = Engine.newBuilder().out(System.out).err(System.err).in(System.in).build();

        Files.deleteIfExists(socket);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        log.println("nix-truffle daemon " + ProcessHandle.current().pid() + " listening on " + socket);
        startIdleWatch(log);
        while (true) {
            SocketChannel ch;
            try {
                ch = server.accept();
            } catch (IOException e) {
                if (retiring) break;
                throw e;
            }
            active.incrementAndGet();
            Thread t = new Thread(() -> {
                try {
                    serve(ch);
                } catch (IOException e) {
                    // the client went away
                } catch (Throwable e) {
                    e.printStackTrace(log);
                } finally {
                    try {
                        ch.close();
                    } catch (IOException ignored) {
                        // gone already
                    }
                    lastActive = System.nanoTime();
                    if (active.decrementAndGet() == 0 && retiring) System.exit(0);
                }
            }, "client");
            t.start();
        }
        if (active.get() == 0) System.exit(0);
    }

    private static void serve(SocketChannel ch) throws Exception {
        DataInputStream in = new DataInputStream(Client.input(ch));
        OutputStream out = Client.output(ch);
        int type = in.read();
        if (type < 0) return;
        DataInputStream req = new DataInputStream(new java.io.ByteArrayInputStream(in.readNBytes(in.readInt())));
        if (req.readInt() != Client.VERSION) {
            exit(out, 1, "nix-truffle: the daemon speaks another protocol version\n");
            return;
        }
        String cwd = Client.readString(req);
        boolean console = req.readBoolean();
        String[] args = new String[req.readInt()];
        for (int i = 0; i < args.length; i++) args[i] = Client.readString(req);
        Map<String, String> env = new HashMap<>();
        for (int i = req.readInt(); i > 0; i--) env.put(Client.readString(req), Client.readString(req));

        if (type == Client.STATUS) {
            exit(out, 0, String.format("daemon %d, up %d s, %d commands served, %d running, heap %d MB used%n", ProcessHandle.current().pid(),
                    (System.currentTimeMillis() - STARTED) / 1000, served.get(), active.get() - 1,
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20));
            return;
        }
        if (type == Client.STOP) {
            exit(out, 0, "");
            retire();
            return;
        }
        // A new build: let a new daemon run it.
        if (retiring || fingerprint() != build) {
            Client.writeFrame(out, Client.STALE, new byte[0], 0);
            retire();
            return;
        }
        served.incrementAndGet();

        Proc.Client client = new Proc.Client(env, cwd, console);
        LinkedBlockingQueue<byte[]> stdin = new LinkedBlockingQueue<>();
        client.out = new PrintStream(new FrameStream(out, Client.STDOUT), true);
        client.err = new PrintStream(new FrameStream(out, Client.STDERR), true);
        client.in = new StdinStream(out, stdin);
        // Frames from the client: its standard input; the end of the connection means it has gone.
        Thread reader = new Thread(() -> {
            try {
                while (true) {
                    int t = in.read();
                    if (t < 0) break;
                    byte[] data = in.readNBytes(in.readInt());
                    if (t == Client.STDIN) stdin.add(data);
                }
            } catch (IOException e) {
                // gone
            }
            stdin.add(new byte[0]);
            Runnable cancel = client.cancel;
            if (cancel != null && !client.done) {
                try {
                    cancel.run();
                } catch (Exception e) {
                    // already closed
                }
            }
        }, "client-reader");
        reader.setDaemon(true);
        reader.start();

        int[] status = {1};
        boolean[] local = {false};
        Thread eval = new Thread(null, () -> {
            Proc.setClient(client);
            try {
                status[0] = Main.run(args);
            } catch (RunLocally e) {
                local[0] = true;
            } catch (Throwable e) {
                e.printStackTrace(client.err);
            }
        }, "nix-eval", Long.getLong("nixtruffle.stackMb", 128) << 20);
        eval.start();
        eval.join();
        client.done = true;
        client.out.flush();
        client.err.flush();
        if (local[0]) {
            Client.writeFrame(out, Client.LOCAL, new byte[0], 0);
        } else {
            exit(out, status[0], "");
        }
    }

    private static void exit(OutputStream out, int status, String message) throws IOException {
        if (!message.isEmpty()) {
            byte[] b = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Client.writeFrame(out, Client.STDOUT, b, b.length);
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        new DataOutputStream(b).writeInt(status);
        Client.writeFrame(out, Client.EXIT, b.toByteArray(), 4);
    }

    /** Stops taking commands; exits when the running ones finish. */
    private static synchronized void retire() {
        if (retiring) return;
        retiring = true;
        try {
            Files.deleteIfExists(socket);
            server.close();
            lockFile.close();
        } catch (IOException e) {
            // exiting anyway
        }
    }

    private static void startIdleWatch(PrintStream log) {
        String idle = System.getenv("NIX_TRUFFLE_DAEMON_IDLE");
        long idleNanos = (idle != null ? Long.parseLong(idle) : 3 * 3600) * 1_000_000_000L;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(Math.min(60_000, Math.max(1000, idleNanos / 1_000_000 / 4)));
                } catch (InterruptedException e) {
                    return;
                }
                if (active.get() == 0 && System.nanoTime() - lastActive > idleNanos) {
                    log.println("idle, exiting");
                    retire();
                    System.exit(0);
                }
            }
        }, "idle-watch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The build this daemon runs, as the modification times and sizes of its class path, to
     * notice when it has been rebuilt.
     */
    private static long fingerprint() throws IOException {
        long h = 17;
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path p = Path.of(entry);
            if (Files.isDirectory(p)) {
                try (Stream<Path> files = Files.walk(p)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        h = h * 31 + f.toString().hashCode();
                        h = h * 31 + f.toFile().lastModified();
                        h = h * 31 + f.toFile().length();
                    }
                }
            } else {
                h = h * 31 + entry.hashCode();
                h = h * 31 + p.toFile().lastModified();
            }
        }
        return h;
    }

    /** Writes to the current client's stream, or the log. */
    private static final class Dispatch extends OutputStream {
        private final java.util.function.Function<Proc.Client, PrintStream> stream;
        private final PrintStream fallback;

        Dispatch(java.util.function.Function<Proc.Client, PrintStream> stream, PrintStream fallback) {
            this.stream = stream;
            this.fallback = fallback;
        }

        private PrintStream target() {
            Proc.Client c = Proc.client();
            return c == null || stream.apply(c) == null ? fallback : stream.apply(c);
        }

        @Override
        public void write(int b) {
            target().write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            target().write(b, off, len);
        }

        @Override
        public void flush() {
            target().flush();
        }
    }

    /** Output to the client, in frames of a type. */
    private static final class FrameStream extends OutputStream {
        private final OutputStream out;
        private final byte type;
        private final byte[] buf = new byte[8192];
        private int n;

        FrameStream(OutputStream out, byte type) {
            this.out = out;
            this.type = type;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (n == buf.length) flush();
            buf[n++] = (byte) b;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (len > buf.length - n) flush();
            if (len >= buf.length) {
                Client.writeFrame(out, type, java.util.Arrays.copyOfRange(b, off, off + len), len);
            } else {
                System.arraycopy(b, off, buf, n, len);
                n += len;
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            if (n > 0) {
                Client.writeFrame(out, type, buf, n);
                n = 0;
            }
        }
    }

    /** The client's standard input, which it sends once asked for. */
    private static final class StdinStream extends InputStream {
        private final OutputStream out;
        private final LinkedBlockingQueue<byte[]> queue;
        private boolean asked, eof;
        private byte[] chunk = new byte[0];
        private int pos;

        StdinStream(OutputStream out, LinkedBlockingQueue<byte[]> queue) {
            this.out = out;
            this.queue = queue;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos == chunk.length) {
                if (eof) return -1;
                if (!asked) {
                    asked = true;
                    Client.writeFrame(out, Client.NEED_STDIN, new byte[0], 0);
                }
                try {
                    chunk = queue.take();
                } catch (InterruptedException e) {
                    throw new java.io.InterruptedIOException();
                }
                pos = 0;
                if (chunk.length == 0) eof = true;
            }
            int n = Math.min(len, chunk.length - pos);
            System.arraycopy(chunk, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
