package nixtruffle.launcher;

import nixtruffle.runtime.Bytes;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code nix-truffle [options] (FILE... | -E EXPR)}: evaluates and prints like
 * {@code nix-instantiate --eval --strict}. Without inputs (or with {@code --repl}) it starts a REPL.
 *
 * <pre>
 *   -E, --expr EXPR     evaluate EXPR instead of a file ('<nixpkgs>' also works as a file)
 *   -A, --attr PATH     select an attribute (functions along the path are auto-called)
 *   --arg NAME EXPR     argument for auto-called functions; --argstr NAME STRING likewise
 *   -I PATH             add to the lookup path (PATH or PREFIX=PATH)
 *   --option NAME VALUE, --extra-experimental-features FEATURES
 *                       settings, as in nix.conf
 *   --instantiate       like nix-instantiate: write the derivations to the store, print .drv paths
 *   --read-only         with --instantiate: only compute and print the .drv paths
 *   --read-write-mode   evaluation may write to the store (evaluation alone doesn't, by default)
 *   --repeat N          evaluate N times (to watch the JIT warm up), print the last result
 *   --time              print the time of every evaluation to stderr
 *   --test              one line per input; errors are printed as "error"
 *   --pbt-server        speak the nix-pbt evaluation protocol on stdin/stdout
 *   eval [INSTALLABLE]  like `nix eval`: FLAKEREF#ATTRPATH, or --file/--expr (see EvalCommand)
 *   flake lock [DIR]    write DIR's flake.lock, like `nix flake lock`
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws InterruptedException {
        int[] status = {0};
        // Lazy evaluation recurses deeply (thunk -> thunk -> ...), so evaluate on a thread with a big
        // stack. Runaway recursion takes proportionally longer to hit the limit, though.
        long stackMb = Long.getLong("nixtruffle.stackMb", 128);
        Thread thread = new Thread(null, () -> status[0] = run(args), "nix-eval", stackMb << 20);
        thread.start();
        thread.join();
        System.out.flush();
        System.exit(status[0]);
    }

    /** Settings from the command line, passed to the language as options. */
    static final class Options {
        final StringBuilder config = new StringBuilder();
        final StringBuilder includePath = new StringBuilder();
        Boolean readOnly;

        Context.Builder builder(boolean defaultReadOnly) {
            return Context.newBuilder().allowAllAccess(true)
                    .option("nix.Config", config.toString())
                    .option("nix.IncludePath", includePath.toString())
                    .option("nix.ReadOnly", String.valueOf(readOnly != null ? readOnly : defaultReadOnly));
        }

        /** Handles a settings flag at {@code args[i]}; returns the number of arguments used (0 if none). */
        int parse(String[] args, int i) {
            switch (args[i]) {
                case "--option" -> {
                    config.append(args[i + 1]).append(" = ").append(args[i + 2]).append('\n');
                    return 3;
                }
                case "--extra-experimental-features", "--experimental-features" -> {
                    config.append(args[i].substring(2)).append(" = ").append(args[i + 1]).append('\n');
                    return 2;
                }
                case "-I", "--include" -> {
                    includePath.append(args[i + 1]).append('\n');
                    return 2;
                }
                case "--read-only", "--readonly-mode" -> {
                    readOnly = true;
                    return 1;
                }
                case "--read-write-mode" -> {
                    readOnly = false;
                    return 1;
                }
                default -> {
                    String name = args[i].startsWith("--") ? args[i].substring(2) : "";
                    // Boolean settings are flags: --pure-eval, --no-pure-eval.
                    if (BOOLEAN_SETTINGS.contains(name)) {
                        config.append(name).append(" = true\n");
                        return 1;
                    }
                    if (name.startsWith("no-") && BOOLEAN_SETTINGS.contains(name.substring(3))) {
                        config.append(name.substring(3)).append(" = false\n");
                        return 1;
                    }
                    if (i + 1 < args.length && (SETTINGS.contains(name) || name.startsWith("extra-") && SETTINGS.contains(name.substring(6)))) {
                        config.append(name).append(" = ").append(args[i + 1]).append('\n');
                        return 2;
                    }
                    return 0;
                }
            }
        }

        private static final Set<String> BOOLEAN_SETTINGS = Set.of("pure-eval", "allow-dirty", "warn-dirty", "use-registries", "trace-verbose",
                "polyglot", "allow-dirty-locks", "restrict-eval");
        private static final Set<String> SETTINGS = Set.of("flake-registry", "tarball-ttl", "access-tokens", "nix-path", "system",
                "commit-lock-file-summary", "experimental-features");
    }

    private record Input(byte[] bytes, boolean isFile, String name) {}

    private static int run(String[] args) {
        Options options = new Options();
        List<Input> inputs = new ArrayList<>();
        List<String> attrPaths = new ArrayList<>();
        StringBuilder autoArgs = new StringBuilder("{ ");
        int repeat = 1;
        boolean time = false;
        boolean test = false;
        boolean instantiate = false;
        boolean pbtServer = false;
        for (int i = 0; i < args.length; ) {
            int used = options.parse(args, i);
            if (used > 0) {
                i += used;
                continue;
            }
            switch (args[i]) {
                case "-E", "--expr" -> inputs.add(new Input(Bytes.get(Bytes.fromJava(args[++i])), false, "«string»"));
                case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                case "--time" -> time = true;
                case "--test" -> test = true;
                case "-A", "--attr" -> attrPaths.add(args[++i]);
                case "--arg" -> autoArgs.append(args[++i]).append(" = (").append(args[++i]).append("); ");
                case "--argstr" -> autoArgs.append(args[++i]).append(" = \"").append(args[++i].replace("\\", "\\\\")
                        .replace("\"", "\\\"").replace("${", "\\${")).append("\"; ");
                case "--instantiate" -> instantiate = true;
                case "--eval", "--strict", "--json" -> {}
                case "--repl" -> {
                    return repl(options);
                }
                case "--pbt-server" -> pbtServer = true;
                case "eval" -> {
                    return EvalCommand.run(options, java.util.Arrays.copyOfRange(args, i + 1, args.length));
                }
                case "flake" -> {
                    return FlakeCommand.run(options, java.util.Arrays.copyOfRange(args, i + 1, args.length));
                }
                case "-h", "--help" -> {
                    System.out.println("usage: nix-truffle [options] (FILE... | -E EXPR | --repl | --pbt-server | eval INSTALLABLE | flake lock)");
                    return 0;
                }
                default -> {
                    String a = args[i];
                    inputs.add(a.startsWith("<") && a.endsWith(">")
                            ? new Input(Bytes.get(Bytes.fromJava("import " + a)), false, a)
                            : new Input(Bytes.get(Bytes.fromJava(new java.io.File(a).getAbsolutePath())), true, new java.io.File(a).getName()));
                }
            }
            i++;
        }
        if (pbtServer) return PbtServer.run(options.builder(false));
        if (inputs.isEmpty()) return repl(options);

        if (attrPaths.isEmpty()) attrPaths.add("");
        boolean hasAutoArgs = autoArgs.length() > 2;
        autoArgs.append("}");

        int status = 0;
        // Like nix-instantiate: --eval doesn't write to the store, instantiation does.
        try (Context context = options.builder(!instantiate).build()) {
            Value internals = context.eval("nix", "__nixTruffle");
            Value show = internals.getMember("show");
            Value evalBytes = internals.getMember("evalBytes");
            Value importFile = internals.getMember("importFile");
            Value findAttrPath = internals.getMember("findAttrPath");
            Value autoCall = internals.getMember("autoCall");
            Value instantiateFn = internals.getMember("instantiate");
            Value autoArgsValue = context.eval("nix", autoArgs.toString());
            boolean write = options.readOnly == null || !options.readOnly;
            for (Input input : inputs) {
                if (test) System.out.println("### " + input.name());
                for (int run = 1; run <= repeat; run++) {
                    long start = System.nanoTime();
                    try {
                        Value root = input.isFile() ? importFile.execute(input.bytes()) : evalBytes.execute(input.bytes());
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        for (String attrPath : attrPaths) {
                            Value v = findAttrPath.execute(autoArgsValue, attrPath, root);
                            if (instantiate) {
                                Value paths = instantiateFn.execute(write, autoArgsValue, v);
                                for (long j = 0; j < paths.getArraySize(); j++) {
                                    out.writeBytes(paths.getArrayElement(j).asString().getBytes(StandardCharsets.UTF_8));
                                    out.write('\n');
                                }
                            } else {
                                if (hasAutoArgs) v = autoCall.execute(autoArgsValue, v);
                                out.writeBytes(show.execute(v).as(byte[].class));
                                out.write('\n');
                            }
                        }
                        if (time) System.err.printf("run %d: %.1f ms%n", run, (System.nanoTime() - start) / 1e6);
                        if (run == repeat) System.out.writeBytes(out.toByteArray());
                    } catch (PolyglotException e) {
                        if (e.isHostException() || e.isInternalError()) throw e;
                        status = 1;
                        if (test) {
                            System.out.println("error");
                        } else {
                            printErr(System.err, describe(e));
                        }
                        break;
                    }
                }
            }
        }
        return status;
    }

    private static int repl(Options options) {
        try (Context context = options.builder(false).build()) {
            return new Repl(context).run();
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    /** Prints a message from the interpreter (a byte string, see {@link Bytes}). */
    static void printErr(PrintStream err, String message) {
        err.write(Bytes.output(message + "\n"), 0, Bytes.output(message + "\n").length);
        err.flush();
    }

    /** The error message (a byte string) with a location. */
    static String describe(PolyglotException e) {
        if (e.isResourceExhausted()) return "error: stack overflow (possible infinite recursion)";
        StringBuilder sb = new StringBuilder("error: ").append(e.getMessage());
        SourceSection at = e.getSourceLocation();
        if (at != null && !e.getMessage().contains("\n       at ")) {
            sb.append("\n       at ").append(at.getSource().getName()).append(':')
              .append(at.getStartLine()).append(':').append(at.getStartColumn());
        }
        return sb.toString();
    }
}
