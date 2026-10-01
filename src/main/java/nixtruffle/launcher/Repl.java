package nixtruffle.launcher;

import nixtruffle.runtime.Bytes;
import nixtruffle.util.Proc;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code nix-truffle repl}: an interactive REPL in the style of {@code nix repl}. The scope is a
 * Nix attrset held here; inputs are parsed so that their free variables resolve into it (see
 * {@code __replEval}). Files and flakes given on the command line are loaded as with {@code :l}
 * and {@code :lf}.
 */
final class Repl {
    static final String USAGE = """
            usage: nix-truffle repl [option...] [FILE | FLAKEREF[#ATTRPATH]...]

            Evaluates expressions interactively, like 'nix repl'. Each FILE (a path, or <nixpkgs>)
            is loaded as with :l, each flake as with :lf (flakes need the 'flakes' feature).
            With --file FILE or --expr EXPR, the arguments are attribute paths into it instead.

            Options: --file FILE, --expr EXPR, --arg NAME EXPR, --argstr NAME STRING (for loaded
            functions), -I PATH, --option NAME VALUE, --NAME VALUE (settings, as in nix.conf).
            """;

    /**
     * Something loaded into the scope: a file ({@code :l}), or an installable ({@code :lf}),
     * which is called with the {@code --arg}s when it comes from {@code --file} (as in CppNix).
     */
    private record Load(String file, String installableJson, boolean call) {}

    /** {@code nix-truffle repl [option...] [FILE | FLAKEREF...]}. */
    static int command(Main.Options options, String[] args) {
        // The REPL needs the terminal.
        if (Proc.client() != null) throw new Daemon.RunLocally();
        String file = null, expr = null;
        List<String> positional = new ArrayList<>();
        StringBuilder nixArgs = new StringBuilder("{ ");
        List<String> jsonArgs = new ArrayList<>();
        for (int i = 0; i < args.length; ) {
            int used = options.parse(args, i);
            if (used > 0) {
                i += used;
                continue;
            }
            String a = args[i];
            if ((a.equals("--file") || a.equals("-f") || a.equals("--expr")) && i + 1 >= args.length
                    || (a.equals("--arg") || a.equals("--argstr")) && i + 2 >= args.length) {
                System.err.println("error: flag '" + a + "' requires an argument");
                return 1;
            }
            switch (a) {
                case "--file", "-f" -> file = args[++i];
                case "--expr" -> expr = args[++i];
                case "--arg" -> {
                    jsonArgs.add(EvalCommand.autoArg("expr", args[i + 1], args[i + 2]));
                    nixArgs.append(args[++i]).append(" = (").append(args[++i]).append("); ");
                }
                case "--argstr" -> {
                    jsonArgs.add(EvalCommand.autoArg("string", args[i + 1], args[i + 2]));
                    nixArgs.append(args[++i]).append(" = ").append(nixString(args[++i])).append("; ");
                }
                // The REPL is impure anyway.
                case "--impure" -> {}
                case "-h", "--help" -> {
                    System.out.print(USAGE);
                    return 0;
                }
                default -> {
                    if (a.startsWith("-")) {
                        System.err.println("error: unrecognised flag '" + a + "'\n\nTry 'nix-truffle repl --help' for more information.");
                        return 1;
                    }
                    positional.add(a);
                }
            }
            i++;
        }
        options.config.append("pure-eval = false\n");
        String autoArgs = nixArgs.append("}").toString();
        List<Load> loads = new ArrayList<>();
        if (file != null || expr != null) {
            if (positional.isEmpty()) positional.add("");
            for (String attrPath : positional) loads.add(new Load(null, installable(attrPath, file == null ? null : Proc.absolute(file), expr, jsonArgs), file != null));
        } else {
            for (String a : positional) loads.add(isFile(a) ? new Load(a, null, false) : new Load(null, installable(a, null, null, jsonArgs), false));
        }
        try {
            return new Repl(options, autoArgs, loads).run();
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Whether a command-line argument is a file to load rather than a flake: {@code <nixpkgs>},
     * a file (or a missing one named *.nix), or a directory with a default.nix and no flake.nix.
     */
    private static boolean isFile(String arg) {
        if (arg.startsWith("<") && arg.endsWith(">")) return true;
        if (arg.contains("#") || arg.matches("[a-z][a-z0-9+]*:.*")) return false;
        File f = new File(Proc.absolute(arg));
        if (f.isFile() || !f.exists() && arg.endsWith(".nix")) return true;
        return f.isDirectory() && new File(f, "default.nix").isFile() && !new File(f, "flake.nix").isFile();
    }

    /** An installable for {@code __nixTruffle.replLoad}, as JSON. */
    private static String installable(String installable, String file, String expr, List<String> autoArgs) {
        return "{\"installable\":" + EvalCommand.quote(installable.isEmpty() && (file != null || expr != null) ? "." : installable)
                + ",\"file\":" + (file == null ? "null" : EvalCommand.quote(file))
                + ",\"expr\":" + (expr == null ? "null" : EvalCommand.quote(expr))
                + ",\"cwd\":" + EvalCommand.quote(Proc.cwd())
                + ",\"autoArgs\":[" + String.join(",", autoArgs) + "]"
                + ",\"lockFlags\":{\"overrideInputs\":[],\"updateInputs\":[],\"inputsFrom\":[],\"overrideFlake\":[],"
                + "\"referenceLockFile\":null,\"outputLockFile\":null}}";
    }

    /** A Nix string literal. */
    private static String nixString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("${", "\\${") + "\"";
    }

    private static final Pattern BINDING = Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_'-]*)\\s*=(?!=)(.*)$", Pattern.DOTALL);
    private static final Pattern ATTR_PATH = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_'-]*(\\.[a-zA-Z_][a-zA-Z0-9_'-]*)*");
    private static final Set<String> KEYWORDS = Set.of("if", "then", "else", "assert", "with", "let", "in", "rec", "inherit", "or");
    private static final String HELP = """
            The following commands are available:

              <expr>        Evaluate and print expression
              <x> = <expr>  Bind expression to variable
              :a <expr>     Add attributes from resulting set to scope
              :l <path>     Load Nix expression and add it to scope (functions are called with
                            the --arg/--argstr arguments, or {})
              :lf <ref>     Load a flake's outputs and add them to scope
              :ll           Show the variables the last :l, :lf or :a added
              :p <expr>     Evaluate and print expression recursively
              :r            Reload all files and flakes (bindings are dropped)
              :t <expr>     Describe result of evaluation
              :q            Exit nix-truffle repl
            """;

    private final Main.Options options;
    /** The {@code --arg}/{@code --argstr} arguments for loaded functions, as a Nix attrset. */
    private final String autoArgs;
    /** What was loaded, in order, for {@code :r}. */
    private final List<Load> loads;
    private Context context;
    private Value eval;
    private Value bind;
    private Value show;
    private Value update;
    /** What the last load added, for {@code :ll}. */
    private List<String> lastLoaded = List.of();
    private Value typeOf;
    private Value load;
    private Value autoCall;
    private final List<String> globals = new ArrayList<>();
    private Value scope;
    private PrintWriter out;

    private Repl(Main.Options options, String autoArgs, List<Load> loads) {
        this.options = options;
        this.autoArgs = autoArgs;
        this.loads = new ArrayList<>(loads);
        start();
        Value names = context.eval("nix", "builtins.attrNames builtins");
        for (long i = 0; i < names.getArraySize(); i++) globals.add("builtins." + names.getArrayElement(i).asString());
        globals.addAll(List.of("builtins", "import", "map", "toString", "throw", "abort", "derivation", "true", "false", "null"));
    }

    /** A fresh context with an empty scope. */
    private void start() {
        if (context != null) context.close(true);
        context = options.build(false);
        eval = context.eval("nix", "__nixTruffle.replEval");
        bind = context.eval("nix", "__nixTruffle.replBind");
        show = context.eval("nix", "__nixTruffle.replShow");
        update = context.eval("nix", "__nixTruffle.replAdd");
        typeOf = context.eval("nix", "builtins.typeOf");
        load = context.eval("nix", "__nixTruffle.replLoad");
        autoCall = context.eval("nix", "__nixTruffle.autoCall");
        scope = context.eval("nix", "{ }");
    }

    /** Loads a file or an installable into the scope. */
    private void load(Load l) {
        if (l.file() != null) {
            String path = l.file().startsWith("<") ? l.file() : "(/. + " + nixString(Proc.absolute(l.file())) + ")";
            addToScope(eval.execute(scope, "let v = import " + path + "; in if builtins.isFunction v then v " + autoArgs + " else v"));
        } else {
            Value v = load.execute((Object) l.installableJson().getBytes(StandardCharsets.UTF_8));
            addToScope(l.call() ? autoCall.execute(context.eval("nix", autoArgs), v) : v);
        }
    }

    /** Runs the loads, reporting errors like the REPL does. */
    private void loadAll() {
        for (Load l : loads) {
            try {
                load(l);
            } catch (PolyglotException e) {
                report(e);
            }
        }
    }

    int run() throws IOException {
        // UTF-8 whatever the platform's charset (a native image's can be ASCII), like Nix's output.
        Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).encoding(StandardCharsets.UTF_8)
                .stdinEncoding(StandardCharsets.UTF_8).stdoutEncoding(StandardCharsets.UTF_8).stderrEncoding(StandardCharsets.UTF_8).build();
        DefaultParser parser = new DefaultParser();
        parser.setEscapeChars(null);
        parser.setQuoteChars(new char[0]);
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("nix-truffle")
                .parser(parser)
                .completer((r, line, candidates) -> complete(line, candidates))
                .variable(LineReader.HISTORY_FILE, Path.of(System.getProperty("user.home"), ".nix-truffle_history"))
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build();
        out = terminal.writer();
        // Ctrl-C while evaluating interrupts the evaluation, not the REPL.
        terminal.handle(Terminal.Signal.INT, signal -> {
            try {
                context.interrupt(Duration.ofSeconds(2));
            } catch (Exception ignored) {
                // nothing was running
            }
        });
        out.println("Welcome to nix-truffle. Type :? for help.");
        out.println();
        loadAll();
        out.flush();

        StringBuilder pending = new StringBuilder();
        while (true) {
            String line;
            try {
                line = reader.readLine(pending.isEmpty() ? "nix-truffle> " : "              ");
            } catch (UserInterruptException e) {
                pending.setLength(0);
                continue;
            } catch (EndOfFileException e) {
                break;
            }
            if (pending.isEmpty() && line.isBlank()) continue;
            pending.append(line).append('\n');
            try {
                boolean keepGoing = handle(pending.toString().strip());
                pending.setLength(0);
                if (!keepGoing) break;
            } catch (PolyglotException e) {
                // An unfinished expression: keep reading lines (an empty line gives up).
                if (isIncomplete(e) && !line.isBlank()) continue;
                pending.setLength(0);
                report(e);
            }
            out.println();
            out.flush();
        }
        context.close();
        return 0;
    }

    private void report(PolyglotException e) {
        if (e.isHostException() || e.isInternalError()) {
            e.printStackTrace(out);
        } else if (e.isInterrupted() || e.isCancelled()) {
            out.println("error: interrupted");
        } else {
            out.println(Bytes.toJava(Main.describe(e)));
        }
    }

    private static boolean isIncomplete(PolyglotException e) {
        String m = e.getMessage();
        return m != null && (m.contains("unexpected end of file") || m.contains("unterminated"));
    }

    /** Returns false to quit. */
    private boolean handle(String input) {
        if (input.startsWith(":")) {
            int space = input.indexOf(' ');
            String cmd = space < 0 ? input : input.substring(0, space);
            String arg = space < 0 ? "" : input.substring(space + 1).strip();
            switch (cmd) {
                case ":q", ":quit" -> { return false; }
                case ":?", ":help" -> out.print(HELP);
                case ":p", ":print" -> out.println(show.execute(Integer.MAX_VALUE, eval.execute(scope, arg)).asString());
                case ":t", ":type" -> out.println("a " + typeOf.execute(eval.execute(scope, arg)).asString());
                case ":a", ":add" -> addToScope(eval.execute(scope, arg));
                case ":l", ":load" -> {
                    Load l = new Load(arg.startsWith("~/") ? System.getProperty("user.home") + arg.substring(1) : arg, null, false);
                    load(l);
                    loads.add(l);
                }
                case ":lf", ":load-flake" -> {
                    Load l = new Load(null, installable(arg, null, null, List.of()), false);
                    load(l);
                    loads.add(l);
                }
                case ":ll", ":last-loaded" -> lastLoaded.forEach(out::println);
                case ":r", ":reload" -> {
                    start();
                    loadAll();
                }
                default -> out.println("error: unknown command '" + cmd + "'");
            }
            return true;
        }
        Matcher m = BINDING.matcher(input);
        if (m.matches() && !KEYWORDS.contains(m.group(1))) {
            scope = bind.execute(scope, m.group(1), m.group(2));
            return true;
        }
        out.println(show.execute(1, eval.execute(scope, input)).asString());
        return true;
    }

    /** Adds the attributes to the scope, and lists them like {@code nix repl}. */
    private void addToScope(Value attrs) {
        scope = update.execute(scope, attrs);
        lastLoaded = new ArrayList<>(attrs.getMemberKeys());
        out.println("Added " + lastLoaded.size() + " variables.");
        if (lastLoaded.isEmpty()) return;
        out.println(String.join(", ", lastLoaded.subList(0, Math.min(20, lastLoaded.size()))));
        if (lastLoaded.size() > 20) out.println("... and " + (lastLoaded.size() - 20) + " more; view with :ll");
    }

    private void complete(ParsedLine line, List<Candidate> candidates) {
        String word = line.word().substring(0, line.wordCursor());
        if (word.startsWith(":")) {
            for (String c : List.of(":a", ":l", ":lf", ":ll", ":p", ":r", ":t", ":q", ":?")) candidates.add(new Candidate(c));
            return;
        }
        int dot = word.lastIndexOf('.');
        try {
            if (dot > 0 && ATTR_PATH.matcher(word.substring(0, dot)).matches()) {
                Value v = eval.execute(scope, word.substring(0, dot));
                if (v.hasMembers()) {
                    for (String key : v.getMemberKeys()) candidates.add(new Candidate(word.substring(0, dot + 1) + key, key, null, null, null, null, false));
                }
                return;
            }
        } catch (PolyglotException e) {
            return;
        }
        for (String key : scope.getMemberKeys()) candidates.add(new Candidate(key));
        for (String key : globals) candidates.add(new Candidate(key));
    }
}
