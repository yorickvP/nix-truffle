package nixtruffle.launcher;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code nix-truffle [options] (FILE... | -E EXPR)}: evaluates and prints like
 * {@code nix-instantiate --eval --strict}. Without inputs (or with {@code --repl}) it starts a REPL.
 *
 * <pre>
 *   -E, --expr EXPR     evaluate EXPR instead of a file ('<nixpkgs>' also works as a file)
 *   -A, --attr PATH     select an attribute (functions along the path are auto-called)
 *   --arg NAME EXPR     argument for auto-called functions; --argstr NAME STRING likewise
 *   --instantiate       like nix-instantiate: write the derivations to the store, print .drv paths
 *   --read-only         with --instantiate: only compute and print the .drv paths
 *   --repeat N          evaluate N times (to watch the JIT warm up), print the last result
 *   --time              print the time of every evaluation to stderr
 *   --test              one line per input; errors are printed as "error"
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
        System.exit(status[0]);
    }

    private static int run(String[] args) {
        List<Source> sources = new ArrayList<>();
        List<String> attrPaths = new ArrayList<>();
        StringBuilder autoArgs = new StringBuilder("{ ");
        int repeat = 1;
        boolean time = false;
        boolean test = false;
        boolean instantiate = false;
        boolean readOnly = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-E", "--expr" -> sources.add(Source.newBuilder("nix", args[++i], "«string»").build());
                    case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                    case "--time" -> time = true;
                    case "--test" -> test = true;
                    case "-A", "--attr" -> attrPaths.add(args[++i]);
                    case "--arg" -> autoArgs.append(args[++i]).append(" = (").append(args[++i]).append("); ");
                    case "--argstr" -> autoArgs.append(args[++i]).append(" = \"").append(args[++i].replace("\\", "\\\\")
                            .replace("\"", "\\\"").replace("${", "\\${")).append("\"; ");
                    case "--instantiate" -> instantiate = true;
                    case "--read-only", "--readonly-mode" -> readOnly = true;
                    case "--eval", "--strict" -> {}
                    case "--repl" -> {
                        return repl();
                    }
                    case "-h", "--help" -> {
                        System.out.println("usage: nix-truffle [--repeat N] [--time] [--test] (FILE... | -E EXPR | --repl)");
                        return 0;
                    }
                    default -> sources.add(args[i].startsWith("<") && args[i].endsWith(">")
                            ? Source.newBuilder("nix", "import " + args[i], args[i]).build()
                            : Source.newBuilder("nix", new File(args[i])).build());
                }
            }
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
        if (sources.isEmpty()) return repl();

        if (attrPaths.isEmpty()) attrPaths.add("");
        boolean hasAutoArgs = autoArgs.length() > 2;
        autoArgs.append("}");

        int status = 0;
        try (Context context = Context.newBuilder().allowAllAccess(true).build()) {
            Value show = context.eval("nix", "builtins.__show");
            Value findAttrPath = context.eval("nix", "builtins.__findAttrPath");
            Value autoCall = context.eval("nix", "builtins.__autoCall");
            Value instantiateFn = context.eval("nix", "builtins.__instantiate");
            Value autoArgsValue = context.eval("nix", autoArgs.toString());
            for (Source source : sources) {
                if (test) System.out.println("### " + source.getName());
                for (int run = 1; run <= repeat; run++) {
                    long start = System.nanoTime();
                    try {
                        Value root = context.eval(source);
                        StringBuilder out = new StringBuilder();
                        for (String attrPath : attrPaths) {
                            Value v = findAttrPath.execute(autoArgsValue, attrPath, root);
                            if (instantiate) {
                                Value paths = instantiateFn.execute(!readOnly, autoArgsValue, v);
                                for (long j = 0; j < paths.getArraySize(); j++) out.append(paths.getArrayElement(j).asString()).append('\n');
                            } else {
                                if (hasAutoArgs) v = autoCall.execute(autoArgsValue, v);
                                out.append(show.execute(v).asString()).append('\n');
                            }
                        }
                        if (time) System.err.printf("run %d: %.1f ms%n", run, (System.nanoTime() - start) / 1e6);
                        if (run == repeat) System.out.print(out);
                    } catch (PolyglotException e) {
                        if (e.isHostException() || e.isInternalError()) throw e;
                        status = 1;
                        if (test) {
                            System.out.println("error");
                        } else {
                            System.err.println(describe(e));
                        }
                        break;
                    }
                }
            }
        }
        return status;
    }

    private static int repl() {
        try (Context context = Context.newBuilder().allowAllAccess(true).build()) {
            return new Repl(context).run();
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

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
