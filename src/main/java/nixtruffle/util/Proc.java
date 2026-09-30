package nixtruffle.util;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * The process a command runs for: its environment, current directory and whether it has a
 * terminal. On the command line that is this process; the daemon ({@code launcher/Daemon.java})
 * runs each client's command on a thread that carries the client's (the threads it starts inherit
 * them).
 */
public final class Proc {
    private Proc() {}

    /**
     * A daemon client's process. Its standard streams are what {@code System.in}, {@code out} and
     * {@code err} are on its threads; {@code cancel} is set to what stops its evaluation.
     */
    public static final class Client {
        public final Map<String, String> env;
        public final String cwd;
        public final boolean console;
        public java.io.InputStream in;
        public java.io.PrintStream out, err;
        public volatile Runnable cancel;
        public volatile boolean done;

        public Client(Map<String, String> env, String cwd, boolean console) {
            this.env = env;
            this.cwd = cwd;
            this.console = console;
        }
    }

    private static final InheritableThreadLocal<Client> CLIENT = new InheritableThreadLocal<>();

    /** The daemon client this thread works for, or null (on the command line). */
    public static Client client() {
        return CLIENT.get();
    }

    public static void setClient(Client client) {
        CLIENT.set(client);
    }

    @TruffleBoundary
    public static String getenv(String name) {
        Client c = CLIENT.get();
        return c == null ? System.getenv(name) : c.env.get(name);
    }

    @TruffleBoundary
    public static Map<String, String> env() {
        Client c = CLIENT.get();
        return c == null ? System.getenv() : c.env;
    }

    /** The current directory (a Java string). */
    @TruffleBoundary
    public static String cwd() {
        Client c = CLIENT.get();
        return c == null ? System.getProperty("user.dir") : c.cwd;
    }

    /** {@code path} made absolute against the current directory, like {@link File#getAbsolutePath}. */
    public static String absolute(String path) {
        File f = new File(path);
        return f.isAbsolute() ? f.getPath() : new File(cwd(), path).getPath();
    }

    /** Whether standard output is a terminal. */
    public static boolean console() {
        Client c = CLIENT.get();
        if (c != null) return c.console;
        return System.console() != null && System.console().isTerminal();
    }

    /**
     * A process builder that runs in the current directory, with the environment. (Java looks
     * programs up in its own process's PATH, so for a client this looks in the client's.)
     */
    public static ProcessBuilder processBuilder(List<String> command) {
        Client c = CLIENT.get();
        if (c == null) return new ProcessBuilder(command);
        List<String> cmd = new java.util.ArrayList<>(command);
        String path = c.env.get("PATH");
        if (!cmd.get(0).contains("/") && path != null) {
            for (String dir : path.split(":")) {
                File f = new File(dir.isEmpty() ? c.cwd : dir, cmd.get(0));
                if (f.isFile() && f.canExecute()) {
                    cmd.set(0, f.getPath());
                    break;
                }
            }
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().putAll(c.env);
        pb.directory(new File(c.cwd));
        return pb;
    }
}
