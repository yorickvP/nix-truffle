package nixtruffle.launcher;

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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An interactive REPL in the style of {@code nix repl}. The scope is a Nix attrset held here;
 * inputs are parsed so that their free variables resolve into it (see {@code __replEval}).
 */
final class Repl {
    private static final Pattern BINDING = Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_'-]*)\\s*=(?!=)(.*)$", Pattern.DOTALL);
    private static final Pattern ATTR_PATH = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_'-]*(\\.[a-zA-Z_][a-zA-Z0-9_'-]*)*");
    private static final Set<String> KEYWORDS = Set.of("if", "then", "else", "assert", "with", "let", "in", "rec", "inherit", "or");
    private static final String HELP = """
            The following commands are available:

              <expr>        Evaluate and print expression
              <x> = <expr>  Bind expression to variable
              :a <expr>     Add attributes from resulting set to scope
              :l <path>     Load Nix expression and add it to scope (functions are called with {})
              :p <expr>     Evaluate and print expression recursively
              :t <expr>     Describe result of evaluation
              :q            Exit nix-truffle repl
            """;

    private final Context context;
    private final Value eval;
    private final Value bind;
    private final Value show;
    private final Value update;
    private final Value typeOf;
    private final List<String> globals = new ArrayList<>();
    private Value scope;
    private PrintWriter out;

    Repl(Context context) {
        this.context = context;
        this.eval = context.eval("nix", "builtins.__replEval");
        this.bind = context.eval("nix", "builtins.__replBind");
        this.show = context.eval("nix", "builtins.__replShow");
        this.update = context.eval("nix", "a: b: a // b");
        this.typeOf = context.eval("nix", "builtins.typeOf");
        this.scope = context.eval("nix", "{ }");
        Value names = context.eval("nix", "builtins.__replGlobals null");
        for (long i = 0; i < names.getArraySize(); i++) globals.add(names.getArrayElement(i).asString());
    }

    int run() throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build();
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
                if (e.isHostException() || e.isInternalError()) {
                    e.printStackTrace(out);
                } else if (e.isInterrupted() || e.isCancelled()) {
                    out.println("error: interrupted");
                } else {
                    out.println(Main.describe(e));
                }
            }
            out.println();
            out.flush();
        }
        return 0;
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
                    String path = arg.startsWith("<") || arg.startsWith("/") || arg.startsWith(".") || arg.startsWith("~") ? arg : "./" + arg;
                    addToScope(eval.execute(scope, "let v = import " + path + "; in if builtins.isFunction v then v { } else v"));
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

    private void addToScope(Value attrs) {
        long before = scope.getMemberKeys().size();
        scope = update.execute(scope, attrs);
        out.println("Added " + (scope.getMemberKeys().size() - before) + " variables.");
    }

    private void complete(ParsedLine line, List<Candidate> candidates) {
        String word = line.word().substring(0, line.wordCursor());
        if (word.startsWith(":")) {
            for (String c : List.of(":a", ":l", ":p", ":t", ":q", ":?")) candidates.add(new Candidate(c));
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
