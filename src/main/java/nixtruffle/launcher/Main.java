package nixtruffle.launcher;

import nixtruffle.runtime.Bytes;
import nixtruffle.util.Proc;
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
 * The command line: {@code nix-truffle eval}, {@code repl} and {@code flake lock} work like Nix's
 * (see {@link EvalCommand}, {@link Repl}, {@link FlakeCommand}), and {@code nix-truffle [options]
 * (FILE... | -E EXPR)} evaluates and prints like {@code nix-instantiate --eval --strict}. See
 * {@link #HELP}.
 */
public final class Main {
    private Main() {}

    static final String HELP = """
            nix-truffle: a Nix evaluator on GraalVM's Truffle

            Usage:
              nix-truffle eval [OPTION...] [INSTALLABLE]
                  Evaluate, like 'nix eval'. INSTALLABLE is FLAKEREF[#ATTRPATH] (default '.'), or
                  an attribute path into --file FILE or --expr EXPR.
              nix-truffle repl [OPTION...] [FILE | FLAKEREF...]
                  Evaluate interactively, like 'nix repl'; files are loaded as with :l, flakes
                  as with :lf.
              nix-truffle flake lock [FLAKEREF]
                  Create or update a flake's lock file, like 'nix flake lock'.
              nix-truffle [OPTION...] (FILE... | -E EXPR)
                  Evaluate and print, like 'nix-instantiate --eval --strict'.
              nix-truffle daemon (status | stop)
                  The daemon that keeps code warm between commands (NIX_TRUFFLE_DAEMON=1).
              nix-truffle lsp
                  A language server for Nix, over stdio (for editors).

            Options for FILE and -E:
              -A, --attr ATTRPATH        select an attribute (functions on the way are called)
              --arg NAME EXPR            argument for the functions that are called
              --argstr NAME STRING       likewise, a string
              --instantiate              write the derivations, print their paths (like
                                         nix-instantiate)
              --read-only                with --instantiate: only print the paths

            Settings, for every command:
              --option NAME VALUE, --NAME VALUE, --[no-]NAME (booleans),
              --extra-experimental-features FEATURES, -I PATH (lookup path)
              They are read from nix.conf and NIX_CONFIG too, as by Nix. Settings of nix-truffle's
              own: eval-cores (threads; default 0, a thread per core and per GB of heap) and
              polyglot (default true: builtins.polyglotEval and import of other languages).

            Environment:
              NIX_TRUFFLE_DAEMON=1       run commands in a daemon that keeps code warm
              NIX_TRUFFLE_JAVA_OPTS      options for the JVM (a GC option replaces nix-truffle's)

            -h, --help shows this, 'nix-truffle COMMAND --help' a command's; --version the version.
            """;

    /** The version, from the pom. */
    static String version() {
        try (java.io.InputStream in = Main.class.getResourceAsStream("/nixtruffle/version")) {
            return in == null ? "unknown" : new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "unknown";
        }
    }

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
            Context.Builder builder = Context.newBuilder().allowAllAccess(true)
                    .option("nix.Config", config.toString())
                    .option("nix.IncludePath", includePath.toString())
                    .option("nix.ReadOnly", String.valueOf(readOnly != null ? readOnly : defaultReadOnly));
            // In the daemon, contexts share its engine, and so the code they parse and compile.
            if (Daemon.engine != null) builder.engine(Daemon.engine);
            return builder;
        }

        /** A context for a command; a daemon client that goes away cancels its evaluation. */
        Context build(boolean defaultReadOnly) {
            Context context = builder(defaultReadOnly).build();
            Proc.Client client = Proc.client();
            if (client != null) client.cancel = () -> context.close(true);
            return context;
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
        private static final Set<String> SETTINGS = Set.of("flake-registry", "tarball-ttl", "access-tokens", "nix-path", "system", "max-call-depth", "eval-cores",
                "commit-lock-file-summary", "experimental-features");
    }

    private record Input(byte[] bytes, boolean isFile, String name) {}

    static int run(String[] args) {
        if (args.length == 0) {
            System.out.print(HELP);
            return 0;
        }
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
                case "repl" -> {
                    return Repl.command(options, java.util.Arrays.copyOfRange(args, i + 1, args.length));
                }
                case "--repl" -> {
                    return Repl.command(options, new String[0]);
                }
                case "lsp" -> {
                    try (Context context = options.build(false)) {
                        return nixtruffle.lsp.LspServer.run(context);
                    } catch (java.io.IOException e) {
                        System.err.println("nix-truffle lsp: " + e.getMessage());
                        return 1;
                    }
                }
                case "daemon" -> {
                    System.err.println("nix-truffle: there is no daemon without NIX_TRUFFLE_DAEMON=1");
                    return 1;
                }
                case "--version" -> {
                    System.out.println("nix-truffle " + version());
                    return 0;
                }
                case "--pbt-server" -> pbtServer = true;
                case "eval" -> {
                    return EvalCommand.run(options, java.util.Arrays.copyOfRange(args, i + 1, args.length));
                }
                case "flake" -> {
                    return FlakeCommand.run(options, java.util.Arrays.copyOfRange(args, i + 1, args.length));
                }
                case "-h", "--help" -> {
                    System.out.print(HELP);
                    return 0;
                }
                default -> {
                    String a = args[i];
                    inputs.add(a.startsWith("<") && a.endsWith(">")
                            ? new Input(Bytes.get(Bytes.fromJava("import " + a)), false, a)
                            : new Input(Bytes.get(Bytes.fromJava(nixtruffle.util.Proc.absolute(a))), true, new java.io.File(a).getName()));
                }
            }
            i++;
        }
        if (pbtServer) {
            if (Proc.client() != null) throw new Daemon.RunLocally();
            return PbtServer.run(options.builder(false));
        }
        if (inputs.isEmpty()) {
            System.err.print(HELP);
            return 1;
        }

        if (attrPaths.isEmpty()) attrPaths.add("");
        boolean hasAutoArgs = autoArgs.length() > 2;
        autoArgs.append("}");

        int status = 0;
        // Like nix-instantiate: --eval doesn't write to the store, instantiation does.
        try (Context context = options.build(!instantiate)) {
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
