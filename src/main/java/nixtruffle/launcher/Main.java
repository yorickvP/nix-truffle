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
 * {@code nix-instantiate --eval --strict}.
 *
 * <pre>
 *   -E, --expr EXPR   evaluate EXPR instead of a file
 *   --repeat N        evaluate N times (to watch the JIT warm up), print the last result
 *   --time            print the time of every evaluation to stderr
 *   --test            one line per input; errors are printed as "error"
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
        int repeat = 1;
        boolean time = false;
        boolean test = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-E", "--expr" -> sources.add(Source.newBuilder("nix", args[++i], "«string»").build());
                    case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                    case "--time" -> time = true;
                    case "--test" -> test = true;
                    case "-h", "--help" -> {
                        System.out.println("usage: nix-truffle [--repeat N] [--time] [--test] (FILE... | -E EXPR)");
                        return 0;
                    }
                    default -> sources.add(Source.newBuilder("nix", new File(args[i])).build());
                }
            }
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
        if (sources.isEmpty()) {
            System.err.println("usage: nix-truffle [--repeat N] [--time] [--test] (FILE... | -E EXPR)");
            return 1;
        }

        int status = 0;
        try (Context context = Context.newBuilder().allowAllAccess(true).build()) {
            Value show = context.eval("nix", "builtins.__show");
            for (Source source : sources) {
                if (test) System.out.println("### " + source.getName());
                for (int run = 1; run <= repeat; run++) {
                    long start = System.nanoTime();
                    try {
                        String out = show.execute(context.eval(source)).asString();
                        if (time) System.err.printf("run %d: %.1f ms%n", run, (System.nanoTime() - start) / 1e6);
                        if (run == repeat) System.out.println(out);
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

    private static String describe(PolyglotException e) {
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
