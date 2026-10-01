package nixtruffle.launcher;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;

import nixtruffle.runtime.Bytes;
import nixtruffle.util.Proc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code nix-truffle eval [options] [installable]}: CppNix's {@code nix eval}. The installable is a
 * flake reference with an optional {@code #attrpath} (default {@code .}), or an attribute path
 * into {@code --file}/{@code --expr}. Evaluation is pure unless {@code --impure} or {@code --file}
 * is given. The command line is passed to the language as JSON ({@code builtins/CliEval.java}).
 */
final class EvalCommand {
    private EvalCommand() {}

    /** Flags that are accepted and ignored: logging, the evaluation cache, building. */
    private static final Set<String> IGNORED = Set.of("--show-trace", "--verbose", "-v", "--quiet", "--debug", "--print-build-logs", "-L",
            "--no-eval-cache", "--offline", "--keep-going", "-k", "--debugger", "--no-build-output", "-Q", "--accept-flake-config");
    private static final Set<String> IGNORED_WITH_ARG = Set.of("--log-format", "--max-jobs", "-j", "--cores", "--eval-store");

    static int run(Main.Options options, String[] args) {
        String installable = null;
        String file = null, expr = null, apply = null, writeTo = null;
        String output = "nix";
        boolean json = false, raw = false, impure = false, readOnly = false;
        Boolean pretty = null;
        Boolean pureEval = null;
        List<String> autoArgs = new ArrayList<>();
        StringBuilder lock = new StringBuilder();
        List<String> overrideInputs = new ArrayList<>(), updateInputs = new ArrayList<>(), inputsFrom = new ArrayList<>(), overrideFlake = new ArrayList<>();
        String referenceLockFile = null, outputLockFile = null;
        try {
            for (int i = 0; i < args.length; ) {
                String a = args[i];
                // Settings: --option, --extra-experimental-features, -I, --NAME VALUE, --[no-]pure-eval, ...
                if (a.equals("--pure-eval")) pureEval = true;
                if (a.equals("--no-pure-eval")) pureEval = false;
                if (a.equals("--option") && i + 2 < args.length && args[i + 1].equals("pure-eval")) pureEval = args[i + 2].equals("true");
                int used = options.parse(args, i);
                if (used > 0) {
                    i += used;
                    continue;
                }
                switch (a) {
                    case "--json" -> json = true;
                    case "--raw" -> raw = true;
                    case "--pretty" -> pretty = true;
                    case "--no-pretty" -> pretty = false;
                    case "--apply" -> apply = arg(args, ++i, a);
                    case "--write-to" -> writeTo = arg(args, ++i, a);
                    case "--read-only" -> readOnly = true;
                    case "--file", "-f" -> file = arg(args, ++i, a);
                    case "--expr" -> expr = arg(args, ++i, a);
                    case "--impure" -> impure = true;
                    case "--arg" -> autoArgs.add(autoArg("expr", arg(args, ++i, a), arg(args, ++i, a)));
                    case "--argstr" -> autoArgs.add(autoArg("string", arg(args, ++i, a), arg(args, ++i, a)));
                    case "--arg-from-file" -> autoArgs.add(autoArg("file", arg(args, ++i, a), arg(args, ++i, a)));
                    case "--arg-from-stdin" -> autoArgs.add(autoArg("string", arg(args, ++i, a), new String(System.in.readAllBytes(), StandardCharsets.UTF_8)));
                    case "--recreate-lock-file" -> {
                        System.err.println("warning: '--recreate-lock-file' is deprecated and will be removed in a future version; use 'nix flake update' instead.");
                        lock.append("\"recreateLockFile\":true,");
                    }
                    case "--no-update-lock-file" -> lock.append("\"updateLockFile\":false,");
                    case "--no-write-lock-file" -> lock.append("\"writeLockFile\":false,");
                    case "--commit-lock-file" -> lock.append("\"commitLockFile\":true,");
                    case "--no-registries" -> {
                        System.err.println("warning: '--no-registries' is deprecated; use '--no-use-registries'");
                        lock.append("\"useRegistries\":false,");
                    }
                    case "--update-input" -> {
                        System.err.println("warning: '--update-input' is a deprecated alias for 'flake update' and will be removed in a future version.");
                        updateInputs.add(quote(arg(args, ++i, a)));
                    }
                    case "--override-input" -> {
                        lock.append("\"writeLockFile\":false,");
                        overrideInputs.add("[" + quote(arg(args, ++i, a)) + "," + quote(arg(args, ++i, a)) + "]");
                    }
                    case "--reference-lock-file" -> referenceLockFile = absolute(arg(args, ++i, a));
                    case "--output-lock-file" -> outputLockFile = absolute(arg(args, ++i, a));
                    case "--inputs-from" -> inputsFrom.add(quote(arg(args, ++i, a)));
                    case "--override-flake" -> overrideFlake.add("[" + quote(arg(args, ++i, a)) + "," + quote(arg(args, ++i, a)) + "]");
                    case "--refresh" -> options.config.append("tarball-ttl = 0\n");
                    case "--help", "-h" -> {
                        System.out.println("usage: nix-truffle eval [option...] [installable]\n\n"
                                + "Like 'nix eval': installable is FLAKEREF[#ATTRPATH] (default '.'), or an attribute path\n"
                                + "into --file FILE or --expr EXPR. Options: --json, --raw, --apply EXPR, --write-to PATH,\n"
                                + "--impure, --read-only, --arg/--argstr NAME VALUE, -I PATH, --option NAME VALUE,\n"
                                + "--override-input PATH REF, --update-input PATH, --no-write-lock-file, ...");
                        return 0;
                    }
                    default -> {
                        if (IGNORED.contains(a)) break;
                        if (IGNORED_WITH_ARG.contains(a)) {
                            arg(args, ++i, a);
                            break;
                        }
                        if (a.startsWith("-") && !a.equals("-")) throw new UsageError("unrecognised flag '" + a + "'");
                        if (installable != null) throw new UsageError("unexpected argument '" + a + "'");
                        installable = a;
                    }
                }
                i++;
            }
            if (raw && json) throw new UsageError("--raw and --json are mutually exclusive");
            if (file != null && expr != null) throw new UsageError("'--file' and '--expr' are exclusive");
            if (file != null && Boolean.TRUE.equals(pureEval)) throw new UsageError("'--file' is not compatible with '--pure-eval'");
        } catch (UsageError e) {
            return usageError(e.getMessage());
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
        if (json) output = "json";
        if (raw) output = "raw";
        // New-style commands evaluate purely, unless --impure or --file (or an explicit setting).
        if (pureEval == null) options.config.append("pure-eval = ").append(!impure && file == null).append('\n');
        if (impure) options.config.append("pure-eval = false\n");
        if (readOnly) options.readOnly = true;
        if (pretty == null) pretty = Proc.console();

        StringBuilder cfg = new StringBuilder("{");
        cfg.append("\"installable\":").append(quote(installable == null ? "." : installable));
        cfg.append(",\"file\":").append(quoteOrNull(file));
        cfg.append(",\"expr\":").append(quoteOrNull(expr));
        cfg.append(",\"apply\":").append(quoteOrNull(apply));
        cfg.append(",\"writeTo\":").append(quoteOrNull(writeTo == null ? null : absolute(writeTo)));
        cfg.append(",\"output\":").append(quote(output));
        cfg.append(",\"pretty\":").append(pretty);
        cfg.append(",\"cwd\":").append(quote(Proc.cwd()));
        cfg.append(",\"autoArgs\":[").append(String.join(",", autoArgs)).append(']');
        cfg.append(",\"lockFlags\":{").append(lock)
                .append("\"overrideInputs\":[").append(String.join(",", overrideInputs)).append("],")
                .append("\"updateInputs\":[").append(String.join(",", updateInputs)).append("],")
                .append("\"inputsFrom\":[").append(String.join(",", inputsFrom)).append("],")
                .append("\"overrideFlake\":[").append(String.join(",", overrideFlake)).append("],")
                .append("\"referenceLockFile\":").append(quoteOrNull(referenceLockFile)).append(',')
                .append("\"outputLockFile\":").append(quoteOrNull(outputLockFile)).append('}');
        cfg.append('}');

        try (Context context = options.build(false)) {
            byte[] out = context.eval("nix", "__nixTruffle").getMember("nixEval")
                    .execute((Object) cfg.toString().getBytes(StandardCharsets.UTF_8)).as(byte[].class);
            System.out.write(out, 0, out.length);
            System.out.flush();
            return 0;
        } catch (PolyglotException e) {
            if (e.isHostException() || e.isInternalError()) throw e;
            String message = e.getMessage();
            if (message != null && message.startsWith("\0usage\0")) return usageError(Bytes.toJava(message.substring(7)));
            Main.printErr(System.err, Main.describe(e));
            return 1;
        }
    }

    private static final class UsageError extends Exception {
        UsageError(String message) {
            super(message);
        }
    }

    private static int usageError(String message) {
        System.err.println("error: " + message + "\n\nTry 'nix --help' for more information.");
        return 1;
    }

    private static String arg(String[] args, int i, String flag) throws UsageError {
        if (i >= args.length) throw new UsageError("flag '" + flag + "' requires an argument");
        return args[i];
    }

    static String autoArg(String kind, String name, String value) {
        return "{\"kind\":" + quote(kind) + ",\"name\":" + quote(name) + ",\"value\":" + quote(value) + "}";
    }

    private static String absolute(String path) {
        return Proc.absolute(path);
    }

    private static String quoteOrNull(String s) {
        return s == null ? "null" : quote(s);
    }

    /** A JSON string literal (the JSON goes to the language as UTF-8). */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
