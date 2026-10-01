package nixtruffle.launcher;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The daemon client, which {@code bin/nix-truffle} runs with {@code NIX_TRUFFLE_DAEMON=1}: a small
 * JVM that loads nothing but this class. It sends the command line, environment and current
 * directory to a daemon ({@link Daemon}) running the same build of nix-truffle with the same JVM
 * options (starting one if there is none), and relays standard input, output and error, and the
 * exit status. A daemon that finds its build changed on disk retires, and the client starts a new
 * one. Commands that need the terminal (the REPL) run in a JVM of their own.
 *
 * <pre>
 *   Client JAVA N JVM-OPTION... CLASSPATH ARG...
 *   Client JAVA N JVM-OPTION... CLASSPATH daemon (status|stop)
 * </pre>
 *
 * <p>The protocol: frames of a type byte, a big-endian length and that many bytes. The client
 * sends a {@link #REQUEST} ({@link #STATUS}, {@link #STOP}), then {@link #STDIN} data if the
 * daemon asks for it with {@link #NEED_STDIN} (an empty frame is the end of input). The daemon
 * sends {@link #STDOUT} and {@link #STDERR} data and then {@link #EXIT}, or {@link #LOCAL} (run
 * the command here) or {@link #STALE} (it runs an old build).
 */
public final class Client {
    private Client() {}

    static final byte REQUEST = 'R', STATUS = 'P', STOP = 'Q', STDIN = 'I';
    static final byte STDOUT = 'O', STDERR = 'E', EXIT = 'X', LOCAL = 'L', STALE = 'S', NEED_STDIN = 'N';
    static final int VERSION = 1;

    private record Launch(String java, List<String> jvmOptions, String classpath, String[] args) {
        List<String> command(List<String> extraOptions, String mainClass, List<String> args) {
            List<String> cmd = new ArrayList<>();
            cmd.add(java);
            cmd.addAll(jvmOptions);
            cmd.addAll(extraOptions);
            cmd.add("-cp");
            cmd.add(classpath);
            cmd.add(mainClass);
            cmd.addAll(args);
            return cmd;
        }
    }

    public static void main(String[] argv) throws Exception {
        int n = Integer.parseInt(argv[1]);
        Launch launch = new Launch(argv[0], List.of(argv).subList(2, 2 + n), argv[2 + n], Arrays.copyOfRange(argv, 3 + n, argv.length));
        Path dir = socketDir();
        String key = key(launch);
        Path socket = dir.resolve(key + ".sock");
        Path log = dir.resolve(key + ".log");
        String[] args = launch.args();
        if (args.length == 2 && args[0].equals("daemon") && (args[1].equals("status") || args[1].equals("stop"))) {
            SocketChannel ch = connect(socket);
            if (ch == null) {
                System.out.println("no daemon is running for this build (" + socket + ")");
                System.exit(args[1].equals("stop") ? 0 : 1);
            }
            try (ch) {
                System.exit(session(ch, args[1].equals("stop") ? STOP : STATUS, args));
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            SocketChannel existing = connect(socket);
            SocketChannel ch = existing != null ? existing : start(launch, socket, log);
            if (ch == null) {
                System.err.println("nix-truffle: the daemon didn't start (see " + log + "); running without it");
                break;
            }
            int status;
            try (ch) {
                status = session(ch, REQUEST, args);
            }
            if (status == RETRY) continue;
            if (status == RUN_LOCALLY) break;
            System.exit(status);
        }
        System.exit(runLocally(launch));
    }

    private static final int RETRY = -1, RUN_LOCALLY = -2;

    /** Sends a request and relays its output; returns the exit status, {@link #RETRY} or {@link #RUN_LOCALLY}. */
    private static int session(SocketChannel ch, byte type, String[] args) throws IOException {
        OutputStream out = output(ch);
        DataInputStream in = new DataInputStream(input(ch));
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(req);
        d.writeInt(VERSION);
        writeString(d, System.getProperty("user.dir"));
        d.writeBoolean(System.console() != null && System.console().isTerminal());
        d.writeInt(args.length);
        for (String a : args) writeString(d, a);
        Map<String, String> env = System.getenv();
        d.writeInt(env.size());
        for (Map.Entry<String, String> e : env.entrySet()) {
            writeString(d, e.getKey());
            writeString(d, e.getValue());
        }
        writeFrame(out, type, req.toByteArray(), req.size());
        Thread pump = null;
        while (true) {
            int t = in.read();
            if (t < 0) {
                System.err.println("nix-truffle: lost the connection to the daemon");
                return 1;
            }
            byte[] data = in.readNBytes(in.readInt());
            switch (t) {
                case STDOUT -> {
                    System.out.write(data);
                    System.out.flush();
                }
                case STDERR -> {
                    System.err.write(data);
                    System.err.flush();
                }
                case EXIT -> {
                    return new DataInputStream(new java.io.ByteArrayInputStream(data)).readInt();
                }
                case NEED_STDIN -> {
                    if (pump == null) {
                        pump = new Thread(() -> pumpStdin(out), "stdin");
                        pump.setDaemon(true);
                        pump.start();
                    }
                }
                case STALE -> {
                    return RETRY;
                }
                case LOCAL -> {
                    return RUN_LOCALLY;
                }
                default -> throw new IOException("unexpected frame " + t + " from the daemon");
            }
        }
    }

    private static void pumpStdin(OutputStream out) {
        byte[] buf = new byte[1 << 16];
        try {
            int n;
            while ((n = System.in.read(buf)) > 0) writeFrame(out, STDIN, buf, n);
            writeFrame(out, STDIN, buf, 0);
        } catch (IOException e) {
            // the daemon has finished
        }
    }

    /** Runs the command in a JVM of its own. */
    private static int runLocally(Launch launch) throws IOException, InterruptedException {
        List<String> cmd = launch.command(List.of(), "nixtruffle.launcher.Main", List.of(launch.args()));
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }

    /** Starts a daemon and waits until it accepts connections; null if it doesn't. */
    private static SocketChannel start(Launch launch, Path socket, Path log) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        // Keep the daemon out of the terminal's process group, so ^C in the terminal doesn't reach it.
        if (onPath("setsid")) cmd.add("setsid");
        // G1 gives memory back to the system: it keeps the heap at most 30% free after the daemon's
        // collection when idle, and grows it less eagerly than by default (a few percent slower).
        // Survivors are promoted at once and collections use all cores, as in bin/nix-truffle.
        cmd.addAll(launch.command(List.of("-XX:+UseG1GC", "-XX:GCTimeRatio=9", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
                        "-XX:MaxTenuringThreshold=0", "-XX:ParallelGCThreads=" + Runtime.getRuntime().availableProcessors()),
                "nixtruffle.launcher.Daemon", List.of(socket.toString())));
        Process p = new ProcessBuilder(cmd).redirectInput(new File("/dev/null")).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < deadline) {
            SocketChannel ch = connect(socket);
            if (ch != null) return ch;
            // Another client's daemon may have won the race to start (this one then exits).
            if (!p.isAlive() && p.exitValue() != 0) return null;
            Thread.sleep(10);
        }
        return null;
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String d : path.split(":")) if (!d.isEmpty() && Files.isExecutable(Path.of(d, program))) return true;
        return false;
    }

    private static SocketChannel connect(Path socket) {
        try {
            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                ch.connect(UnixDomainSocketAddress.of(socket));
                return ch;
            } catch (IOException e) {
                ch.close();
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code $XDG_RUNTIME_DIR/nix-truffle}, or {@code ~/.cache/nix-truffle/daemon}. */
    static Path socketDir() throws IOException {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        Path dir;
        if (runtime != null && !runtime.isEmpty() && Files.isDirectory(Path.of(runtime))) {
            dir = Path.of(runtime, "nix-truffle");
        } else {
            String cache = System.getenv("XDG_CACHE_HOME");
            dir = Path.of(cache != null && !cache.isEmpty() ? cache : System.getProperty("user.home") + "/.cache", "nix-truffle", "daemon");
        }
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        return dir;
    }

    /** One daemon per Java, JVM options and class path. */
    private static String key(Launch launch) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(launch.java().getBytes(StandardCharsets.UTF_8));
        for (String o : launch.jvmOptions()) md.update(("\0" + o).getBytes(StandardCharsets.UTF_8));
        md.update(("\0" + launch.classpath()).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(md.digest(), 0, 8);
    }

    // ---------------------------------------------------------- framing

    static void writeFrame(OutputStream out, byte type, byte[] data, int len) throws IOException {
        byte[] frame = new byte[5 + len];
        frame[0] = type;
        ByteBuffer.wrap(frame, 1, 4).putInt(len);
        System.arraycopy(data, 0, frame, 5, len);
        synchronized (out) {
            out.write(frame);
        }
    }

    static void writeString(DataOutputStream d, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        d.writeInt(b.length);
        d.write(b);
    }

    static String readString(DataInputStream d) throws IOException {
        return new String(d.readNBytes(d.readInt()), StandardCharsets.UTF_8);
    }

    /**
     * Streams on the channel. (Not {@code Channels.newInputStream}: its reads hold a lock that
     * writes need, and the two directions are used by different threads.)
     */
    static InputStream input(SocketChannel ch) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] b = new byte[1];
                return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                int n = ch.read(ByteBuffer.wrap(b, off, len));
                return n;
            }
        };
    }

    static OutputStream output(SocketChannel ch) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                ByteBuffer buf = ByteBuffer.wrap(b, off, len);
                while (buf.hasRemaining()) ch.write(buf);
            }
        };
    }
}
