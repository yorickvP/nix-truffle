package nixtruffle.lsp;

import com.oracle.truffle.api.source.Source;
import nixtruffle.parser.Expr;
import nixtruffle.parser.Parser;
import nixtruffle.runtime.Bytes;
import nixtruffle.util.Json;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code nix-truffle lsp}: a language server for Nix, over stdio (JSON-RPC with
 * {@code Content-Length} headers). It parses with nix-truffle's parser and resolves names as
 * nix-truffle does ({@link Scopes}): syntax errors and undefined variables as diagnostics, unused
 * bindings as hints, definitions and references of variables, the files of path literals, hover,
 * and completion of the names in scope.
 *
 * <p>And it evaluates ({@link Evaluate}): completion of attributes ({@code pkgs.}, {@code lib.},
 * {@code config.services.}) and hover on them, with what a file's functions would get for their
 * arguments guessed by {@link #resolver}: the workspace's flake ({@code self}, its inputs, its
 * first NixOS configuration's {@code config}, {@code options} and {@code pkgs}), else
 * {@code import <nixpkgs> { }}, and other names from {@code pkgs} (as callPackage does). The
 * client's {@code initializationOptions} can say {@code nixpkgs} and {@code nixos} (expressions).
 *
 * <p>Requests that evaluate run one at a time on a worker thread, on the document as it was when
 * they came, and an evaluation that takes longer than {@code evalTimeout} (seconds, default 10)
 * or whose request is cancelled is interrupted. Everything else (documents, diagnostics, what
 * needs no evaluation) is answered by the thread that reads the messages, so meanwhile too.
 */
public final class LspServer {
    private static final List<String> KEYWORDS = List.of("assert", "else", "if", "in", "inherit", "let", "or", "rec", "then", "with");

    private final InputStream in;
    private final OutputStream out;
    /** The context evaluation is in: a new one after a {@link #reload} (the worker's). */
    private volatile Context context;
    private final java.util.function.Supplier<Context> contexts;
    private final Set<String> globals = new HashSet<>();
    private final List<String> builtinNames = new ArrayList<>();
    private final Map<String, Doc> docs = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean shutdown;
    /** The workspace's directory, and the client's initializationOptions. */
    private volatile String rootPath;
    private volatile Map<String, Object> initOptions = Map.of();
    /** Whether the client takes snippets in completion items. */
    private volatile boolean snippets;

    /** The thread that evaluates (all of the fields below are its), and the timeouts' timer. */
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> daemon(r, "lsp-eval"));
    private final java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "lsp-timer"));
    private final Map<Object, Task> tasks = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Task running;
    private Value lspEval;
    private Value describe;

    /** An open document as it is at one time, and its parse. */
    private static final class Doc {
        final String uri;
        final String text;
        final Lines lines;
        final Expr root;
        final Parser.SyntaxError error;
        /** When the text doesn't parse: it repaired, for the rest of its analysis (or null). */
        final Repair repair;
        final Scopes scopes;
        /** The last text that parsed, and its tree: for completion while the text doesn't. */
        final String goodText;
        final Expr goodRoot;

        Doc(String uri, String text, Doc previous, Set<String> globals) {
            this.uri = uri;
            this.text = text;
            this.lines = new Lines(text);
            Expr root = null;
            Parser.SyntaxError error = null;
            Repair repair = null;
            Scopes scopes = null;
            try {
                root = parse(uri, text);
                scopes = Scopes.analyze(text, root, globals);
            } catch (Parser.SyntaxError e) {
                error = e;
                repair = Repair.of(uri, text, e);
                if (repair != null) {
                    Repair r = repair;
                    scopes = Scopes.analyze(r.text, r.root, globals).mapped(r::original, r::inserted);
                }
            }
            this.root = root;
            this.error = error;
            this.repair = repair;
            this.scopes = scopes;
            this.goodText = root != null ? text : previous != null ? previous.goodText : null;
            this.goodRoot = root != null ? root : previous != null ? previous.goodRoot : null;
        }
    }

    /** A request that evaluates: answered by the worker. */
    private static final class Task {
        final Object id;
        final String method;
        final Map<String, Object> params;
        final Doc doc;
        volatile boolean cancelled;
        volatile String interrupted;

        Task(Object id, String method, Map<String, Object> params, Doc doc) {
            this.id = id;
            this.method = method;
            this.params = params;
            this.doc = doc;
        }
    }

    private static Thread daemon(Runnable r, String name) {
        // Evaluation recurses deeply: the stack the command line's evaluation has (see Main).
        Thread t = new Thread(null, r, name, Long.getLong("nixtruffle.stackMb", 128) << 20);
        t.setDaemon(true);
        return t;
    }

    LspServer(InputStream in, OutputStream out, java.util.function.Supplier<Context> contexts) {
        this.in = new BufferedInputStream(in);
        this.out = out;
        this.contexts = contexts;
        this.context = contexts.get();
        Value names = context.eval("nix", "__nixTruffle.globals null");
        for (long i = 0; i < names.getArraySize(); i++) globals.add(names.getArrayElement(i).asString());
        Value builtins = context.eval("nix", "builtins.attrNames builtins");
        for (long i = 0; i < builtins.getArraySize(); i++) builtinNames.add(builtins.getArrayElement(i).asString());
    }

    public static int run(java.util.function.Supplier<Context> contexts) throws IOException {
        LspServer server = new LspServer(System.in, System.out, contexts);
        try {
            return server.serve();
        } finally {
            server.context.close(true);
        }
    }

    // ------------------------------------------------------------ transport

    int serve() throws IOException {
        try {
            while (true) {
                Map<String, Object> msg = read();
                if (msg == null) return shutdown ? 0 : 1;
                String method = msg.get("method") instanceof String m ? Bytes.toJava(m) : null;
                Object id = msg.get("id");
                if (method == null) continue;
                if (method.equals("exit")) return shutdown ? 0 : 1;
                Map<String, Object> params = msg.get("params") instanceof Map<?, ?> ? Json.obj(msg.get("params")) : Map.of();
                if (method.equals("$/cancelRequest")) {
                    cancel(params.get("id"));
                } else if (EVALUATING.contains(method)) {
                    Task task = new Task(id, method, params, docOf(method, params));
                    if (id != null) tasks.put(id, task);
                    worker.execute(() -> run(task));
                } else {
                    answer(id, () -> handle(method, params));
                }
            }
        } finally {
            worker.shutdownNow();
            timer.shutdownNow();
        }
    }

    /** The requests that may evaluate. */
    private static final Set<String> EVALUATING = Set.of("textDocument/completion", "textDocument/hover", "textDocument/definition", "completionItem/resolve");

    /** The document a request is about, as it is now. */
    private Doc docOf(String method, Map<String, Object> params) {
        Object uri = method.equals("completionItem/resolve")
                ? params.get("data") instanceof Map<?, ?> d ? ((Map<?, ?>) d).get("uri") : null
                : params.get("textDocument") instanceof Map<?, ?> td ? ((Map<?, ?>) td).get("uri") : null;
        return uri instanceof String u ? docs.get(Bytes.toJava(u)) : null;
    }

    private void answer(Object id, java.util.function.Supplier<Object> f) {
        try {
            Object result = f.get();
            if (id != null) respond(id, result == null ? Json.NULL : result, null);
        } catch (Unknown e) {
            if (id != null) respondQuietly(id, null, error(-32601, "unknown method"));
        } catch (Refused e) {
            if (id != null) respondQuietly(id, null, error(-32803, e.getMessage()));
        } catch (RuntimeException e) {
            if (id != null) respondQuietly(id, null, error(-32603, String.valueOf(e)));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void respondQuietly(Object id, Object result, Map<String, Object> error) {
        try {
            respond(id, result, error);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Runs an evaluating request on the worker, interrupted when it takes too long or is cancelled. */
    private void run(Task task) {
        if (task.cancelled) {
            if (task.id != null) tasks.remove(task.id);
            respondQuietly(task.id, null, error(-32800, "cancelled"));
            return;
        }
        long setting = initOptions.get("evalTimeout") instanceof Number n ? n.longValue() : 10;
        long seconds = task.method.equals("warmUp") ? Math.max(setting, 120) : setting;
        running = task;
        java.util.concurrent.ScheduledFuture<?> timeout = timer.schedule(() -> interrupt(task, "timed out after " + seconds + " s"), seconds, java.util.concurrent.TimeUnit.SECONDS);
        try {
            Object result = null;
            RuntimeException failure = null;
            try {
                result = handleEvaluating(task);
            } catch (RuntimeException e) {
                failure = e;
            }
            if (task.interrupted != null) log(task.method + ": evaluation " + task.interrupted);
            if (task.id == null) return;
            if (task.cancelled) respondQuietly(task.id, null, error(-32800, "cancelled"));
            else if (failure instanceof Unknown) respondQuietly(task.id, null, error(-32601, "unknown method"));
            else if (failure != null) respondQuietly(task.id, null, error(-32603, String.valueOf(failure)));
            else respondQuietly(task.id, result == null ? Json.NULL : result, null);
        } finally {
            timeout.cancel(false);
            running = null;
            if (task.id != null) tasks.remove(task.id);
        }
    }

    private void cancel(Object id) {
        Task task = id == null ? null : tasks.get(id);
        if (task == null) return;
        task.cancelled = true;
        interrupt(task, "cancelled");
    }

    /** Interrupts the evaluation of {@code task} if it's the one running. */
    private void interrupt(Task task, String why) {
        if (running != task) return;
        task.interrupted = why;
        try {
            context.interrupt(java.time.Duration.ofSeconds(5));
        } catch (java.util.concurrent.TimeoutException | RuntimeException e) {
            log("interrupting: " + e);
        }
    }

    private Object handleEvaluating(Task task) {
        return switch (task.method) {
            case "completionItem/resolve" -> resolveItem(task.params);
            case "textDocument/completion" -> at(task, this::completion);
            case "textDocument/hover" -> at(task, this::hover);
            case "textDocument/definition" -> at(task, this::definition);
            case "warmUp" -> {
                importing("");
                yield null;
            }
            default -> throw new Unknown();
        };
    }

    private static final class Unknown extends RuntimeException {}

    private Map<String, Object> read() throws IOException {
        int length = -1;
        while (true) {
            String line = readLine();
            if (line == null) return null;
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) length = Integer.parseInt(line.substring(colon + 1).trim());
        }
        if (length < 0) return null;
        byte[] body = in.readNBytes(length);
        if (body.length < length) return null;
        return Json.obj(Json.parse(Bytes.of(body)));
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) return sb.isEmpty() ? null : sb.toString();
            if (c == '\n') return sb.toString().replace("\r", "");
            sb.append((char) c);
        }
    }

    private synchronized void send(Map<String, Object> msg) throws IOException {
        msg.put("jsonrpc", "2.0");
        byte[] body = Json.write(msg).getBytes(StandardCharsets.ISO_8859_1);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void respond(Object id, Object result, Map<String, Object> error) throws IOException {
        Map<String, Object> msg = new TreeMap<>();
        msg.put("id", id);
        if (error != null) msg.put("error", error);
        else msg.put("result", result);
        send(msg);
    }

    private void notify(String method, Object params) {
        Map<String, Object> msg = new TreeMap<>();
        msg.put("method", method);
        msg.put("params", params);
        try {
            send(msg);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Map<String, Object> error(int code, String message) {
        return obj("code", (long) code, "message", Bytes.fromJava(message));
    }

    /** A JSON object of alternating keys and values. */
    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------ requests

    private Object handle(String method, Map<String, Object> params) {
        return switch (method) {
            case "initialize" -> {
                Object rootUri = params.get("rootUri");
                if (rootUri instanceof String u) rootPath = Path.of(URI.create(Bytes.toJava(u))).toString();
                if (params.get("initializationOptions") instanceof Map<?, ?>) initOptions = Json.obj(params.get("initializationOptions"));
                snippets = params.get("capabilities") instanceof Map<?, ?> caps && caps.get("textDocument") instanceof Map<?, ?> td
                        && td.get("completion") instanceof Map<?, ?> cm && cm.get("completionItem") instanceof Map<?, ?> ci
                        && Boolean.TRUE.equals(ci.get("snippetSupport"));
                yield obj("capabilities", obj(
                        "textDocumentSync", obj("openClose", true, "change", 1L, "save", obj("includeText", false)),
                        "definitionProvider", true,
                        "referencesProvider", true,
                        "documentHighlightProvider", true,
                        "documentSymbolProvider", true,
                        "renameProvider", obj("prepareProvider", true),
                        "hoverProvider", true,
                        "completionProvider", obj("triggerCharacters", List.of("."), "resolveProvider", true)),
                        "serverInfo", obj("name", "nix-truffle"));
            }
            case "initialized" -> {
                // What the configurations import, ahead of the first request that needs it.
                if (rootPath != null && Files.exists(Path.of(rootPath, "flake.nix"))) {
                    Task warmUp = new Task(null, "warmUp", Map.of(), null);
                    worker.execute(() -> run(warmUp));
                }
                yield null;
            }
            case "$/setTrace" -> null;
            case "workspace/didChangeConfiguration" -> {
                // { settings: { "nix-truffle": {...} } }, or the settings themselves
                if (params.get("settings") instanceof Map<?, ?> st) {
                    Map<String, Object> all = Json.obj(st);
                    initOptions = all.get("nix-truffle") instanceof Map<?, ?> mine ? Json.obj(mine) : all;
                    worker.execute(this::reload);
                }
                yield null;
            }
            case "textDocument/didSave" -> {
                // The flake, or a module's options or imports, may have changed: evaluate them again.
                String uri = Bytes.toJava(Json.str(Json.obj(params.get("textDocument")).get("uri")));
                Doc doc = docs.get(uri);
                if (uri.endsWith("/flake.nix") || uri.endsWith("/flake.lock")
                        || doc != null && (doc.text.contains("mkOption") || doc.text.contains("imports"))) {
                    worker.execute(this::reload);
                }
                yield null;
            }
            case "shutdown" -> {
                shutdown = true;
                yield null;
            }
            case "textDocument/didOpen" -> {
                Map<String, Object> td = Json.obj(params.get("textDocument"));
                update(Bytes.toJava(Json.str(td.get("uri"))), Bytes.toJava(Json.str(td.get("text"))));
                yield null;
            }
            case "textDocument/didChange" -> {
                String uri = Bytes.toJava(Json.str(Json.obj(params.get("textDocument")).get("uri")));
                List<Object> changes = Json.arr(params.get("contentChanges"));
                update(uri, Bytes.toJava(Json.str(Json.obj(changes.getLast()).get("text"))));
                yield null;
            }
            case "textDocument/didClose" -> {
                String uri = Bytes.toJava(Json.str(Json.obj(params.get("textDocument")).get("uri")));
                docs.remove(uri);
                notify("textDocument/publishDiagnostics", obj("uri", Bytes.fromJava(uri), "diagnostics", List.of()));
                yield null;
            }
            case "textDocument/references" -> at(params, this::references);
            case "textDocument/documentHighlight" -> at(params, this::highlight);
            case "textDocument/prepareRename" -> at(params, (doc, offset) -> prepareRename(doc, offset));
            case "textDocument/rename" -> at(params, (doc, offset) -> rename(doc, offset, Bytes.toJava(Json.str(params.get("newName")))));
            case "textDocument/documentSymbol" -> {
                Doc doc = docs.get(Bytes.toJava(Json.str(Json.obj(params.get("textDocument")).get("uri"))));
                if (doc == null) yield List.of();
                if (doc.root != null) yield DocumentSymbols.of(doc.text, doc.root, doc.lines::position);
                Repair r = doc.repair;
                yield r == null ? List.of() : DocumentSymbols.of(r.text, r.root, o -> doc.lines.position(r.original(o)));
            }
            default -> {
                if (method.startsWith("$/")) yield null;
                throw new Unknown();
            }
        };
    }

    private interface AtOffset {
        Object apply(Doc doc, int offset);
    }

    private Object at(Map<String, Object> params, AtOffset f) {
        return at(docs.get(Bytes.toJava(Json.str(Json.obj(params.get("textDocument")).get("uri")))), params, f);
    }

    private Object at(Task task, AtOffset f) {
        return at(task.doc, task.params, f);
    }

    private static Object at(Doc doc, Map<String, Object> params, AtOffset f) {
        if (doc == null) return null;
        Map<String, Object> p = Json.obj(params.get("position"));
        return f.apply(doc, doc.lines.offset(((Number) p.get("line")).intValue(), ((Number) p.get("character")).intValue()));
    }

    // ------------------------------------------------------------ documents

    private void update(String uri, String text) {
        Doc doc = new Doc(uri, text, docs.get(uri), globals);
        docs.put(uri, doc);
        notify("textDocument/publishDiagnostics", obj("uri", Bytes.fromJava(uri), "diagnostics", diagnostics(doc)));
    }

    private static Expr parse(String uri, String text) {
        return new Parser(Source.newBuilder("nix", text, uri).build()).parseFile();
    }

    private List<Object> diagnostics(Doc doc) {
        List<Object> out = new ArrayList<>();
        if (doc.error != null) {
            List<int[]> offsets = doc.repair != null ? doc.repair.errorOffsets : List.<int[]>of(new int[] {doc.error.offset});
            List<String> messages = doc.repair != null ? doc.repair.errorMessages : List.of(doc.error.detail);
            for (int i = 0; i < offsets.size(); i++) {
                int at = Math.min(offsets.get(i)[0], doc.text.length());
                out.add(diagnostic(doc, at, Math.min(at + 1, doc.text.length()), 1, Bytes.toJava(messages.get(i)), null));
            }
            if (doc.scopes == null) return out;
        }
        Set<Scopes.Def> used = new HashSet<>();
        for (Scopes.Use u : doc.scopes.uses) {
            if (u.def() != null) used.add(u.def());
            if (u.kind() == Scopes.Kind.UNDEFINED) out.add(diagnostic(doc, u.pos(), u.pos() + u.name().length(), 1, "undefined variable '" + u.name() + "'", null));
        }
        for (Scopes.Def d : doc.scopes.defs) {
            if (used.contains(d) || d.kind().equals("rec") || d.name().startsWith("_")) continue;
            // Hints, which editors grey out ("unnecessary"), not warnings: a module's
            // `{ config, lib, pkgs, ... }` often has unused arguments.
            out.add(diagnostic(doc, d.pos(), d.pos() + d.name().length(), 4, "unused " + (d.kind().equals("let") ? "binding" : "argument") + " '" + d.name() + "'", List.of(1L)));
        }
        return out;
    }

    private Map<String, Object> diagnostic(Doc doc, int start, int end, long severity, String message, List<Object> tags) {
        Map<String, Object> d = obj("range", range(doc, start, end), "severity", severity, "source", "nix-truffle", "message", Bytes.fromJava(message));
        if (tags != null) d.put("tags", tags);
        return d;
    }

    private static Map<String, Object> range(Doc doc, int start, int end) {
        return obj("start", doc.lines.position(start), "end", doc.lines.position(end));
    }

    // ------------------------------------------------------------ features

    /** The variable at an offset. */
    private static Scopes.Use useAt(Doc doc, int offset) {
        if (doc.scopes == null) return null;
        for (Scopes.Use u : doc.scopes.uses) if (u.pos() <= offset && offset <= u.pos() + u.name().length()) return u;
        return null;
    }

    private static Scopes.Def defAt(Doc doc, int offset) {
        if (doc.scopes == null) return null;
        for (Scopes.Def d : doc.scopes.defs) if (d.pos() <= offset && offset <= d.pos() + d.name().length()) return d;
        return null;
    }

    private Object definition(Doc doc, int offset) {
        int[] named = inheritedNameAt(doc, offset);
        if (named != null) {
            Value from = evaluateFrom(doc, named[0], named[0], named[2]);
            Object found = from == null ? null : locations(from, doc.text.substring(named[0], named[1]));
            if (found != null) return found;
        }
        Scopes.Use u = useAt(doc, offset);
        // An inherited name (`inherit (lib) mkIf;`): its definition in what it's inherited from.
        Scopes.Def inherited = u != null ? u.def() : defAt(doc, offset);
        if (inherited != null && inherited.inheritedFrom()) {
            int at = u != null ? u.pos() : inherited.pos();
            Value from = evaluateFrom(doc, at, at, inherited.fromPos());
            Object found = from == null ? null : locations(from, inherited.name());
            if (found != null) return found;
        }
        // A function's attribute argument (`{ fetchFromGitHub, ... }:`): where its value comes
        // from (the package set's fetchFromGitHub), else the argument.
        Scopes.Def formal = u != null ? u.def() : defAt(doc, offset);
        Value from = formalSource(doc, formal);
        if (from != null) {
            Object found = locations(from, formal.name());
            if (found != null) return found;
        }
        if (u != null && u.def() != null) return location(doc.uri, doc, u.def().pos(), u.def().name().length());
        Scopes.Def d = defAt(doc, offset);
        if (d != null) return location(doc.uri, doc, d.pos(), d.name().length());
        if (doc.scopes != null) {
            for (Scopes.PathRef p : doc.scopes.paths) {
                if (p.pos() <= offset && offset <= p.pos() + p.text().length()) {
                    Path file = pathFile(doc.uri, p.text());
                    if (file != null) return fileLocation(file.toString(), 1, 1);
                }
            }
        }
        // Evaluated: an option's declarations, an attribute's definition, a name from `with`.
        int start = offset, end = offset;
        while (start > 0 && isNameChar(doc.text.charAt(start - 1))) start--;
        while (end < doc.text.length() && isNameChar(doc.text.charAt(end))) end++;
        if (start == end) return null;
        String name = doc.text.substring(start, end);
        List<String> optionPath = optionPath(doc, start, start);
        if (optionPath != null) {
            Value opts = optionsAt(doc, optionPath);
            return opts == null || !opts.hasMember(name) ? null : locations(opts, name);
        }
        if (start > 0 && doc.text.charAt(start - 1) == '.') {
            String parent = pathBefore(doc.text, start - 1);
            Value p = parent.isEmpty() ? null : evaluate(doc, start, start, parent);
            return p == null ? null : locations(p, name);
        }
        if (u != null && u.kind() == Scopes.Kind.WITH) {
            Value env = withDefining(doc, start, name);
            return env == null ? null : locations(env, name);
        }
        return null;
    }

    /** For a function's attribute argument: the set its value comes from (the package set, ...), or null. */
    private Value formalSource(Doc doc, Scopes.Def formal) {
        if (formal == null || !formal.kind().equals("argument") || formal.renamable()) return null;
        try {
            Value from = resolver(doc).execute(context.eval("nix", "name: { where = name; }").execute(formal.name()));
            return from == null || from.isNull() ? null : from;
        } catch (org.graalvm.polyglot.PolyglotException e) {
            if (e.isInterrupted() || e.isCancelled()) throw e;
            log("where " + formal.name() + " comes from: " + e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> fileLocation(String file, long line, long column) {
        Map<String, Object> pos = obj("line", Math.max(0, line - 1), "character", Math.max(0, column - 1));
        return obj("uri", Bytes.fromJava(Path.of(file).toUri().toString()), "range", obj("start", pos, "end", pos));
    }

    /** Where {@code parent.name} is defined: as {@link #locate} says. */
    private Object locations(Value parent, String name) {
        try {
            Value ps = locate().execute(parent, name);
            List<Object> out = new ArrayList<>();
            for (long i = 0; i < ps.getArraySize(); i++) {
                Value p = ps.getArrayElement(i);
                out.add(fileLocation(p.getMember("file").asString(), p.getMember("line").asLong(), p.getMember("column").asLong()));
            }
            return out.isEmpty() ? null : out;
        } catch (org.graalvm.polyglot.PolyglotException e) {
            log("locating " + name + ": " + e.getMessage());
            return null;
        }
    }

    private Value locate;

    /**
     * {@code parent: name: [ { file, line, column } ]}: an option's declarations, a package's
     * meta.position, a function's own position, else the attribute's.
     */
    private Value locate() {
        if (locate == null) {
            locate = context.eval("nix", """
                    parent: name:
                    let
                      safe = x: let r = builtins.tryEval x; in if r.success then r.value else null;
                      v = parent.${name};
                      t = builtins.typeOf v;
                      option = t == "set" && (v._type or null) == "option";
                      drv = t == "set" && (v.type or null) == "derivation";
                      fileLine = s: let m = builtins.match "(.*):([0-9]+)" s; in
                        if m == null then { file = s; line = 1; column = 1; }
                        else { file = builtins.elemAt m 0; line = builtins.fromJSON (builtins.elemAt m 1); column = 1; };
                      attr = safe (builtins.unsafeGetAttrPos name parent);
                      declared = safe (v.declarationPositions or null);
                      found =
                        if option then
                          (if declared != null then declared
                           else map (f: { file = toString f; line = 1; column = 1; }) (v.declarations or [ ]))
                        # (a fetched source's meta.position is its fetcher's code: the attribute's is better)
                        else if drv && safe (v.meta.position or null) != null
                          && !(attr != null && builtins.match ".*/pkgs/build-support/.*" v.meta.position != null) then [ (fileLine v.meta.position) ]
                        else if t == "lambda" && safe (__nixTruffle.lambdaPos v) != null then [ (__nixTruffle.lambdaPos v) ]
                        else if attr != null then [ attr ]
                        else [ ];
                    in builtins.filter (p: p != null && builtins.isString (p.file or null) && builtins.substring 0 1 p.file == "/")
                      (if found == null then [ ] else found)
                    """);
        }
        return locate;
    }

    // ------------------------------------------------------------ with

    /** The sets of the {@code with}s around the cursor, innermost first, or null. */
    private Value withEnvs(Doc doc, int wordStart, int offset) {
        Parseable p = parseable(doc, wordStart, offset);
        if (p == null) return null;
        try {
            if (lspEval == null) lspEval = context.eval("nix", "__nixTruffle.lspEval");
            Map<String, Object> request = obj("file", Bytes.fromJava(Path.of(URI.create(doc.uri)).toString()), "text", Bytes.fromJava(p.text),
                    "offset", (long) p.text.substring(0, p.offset).getBytes(StandardCharsets.UTF_8).length, "withs", true);
            return lspEval.execute(Json.write(request).getBytes(StandardCharsets.ISO_8859_1), resolver(doc));
        } catch (org.graalvm.polyglot.PolyglotException | IllegalArgumentException e) {
            log("with: " + e.getMessage());
            return null;
        }
    }

    /** The innermost {@code with}'s set around the cursor that has {@code name}, or null. */
    private Value withDefining(Doc doc, int offset, String name) {
        Value envs = withEnvs(doc, offset, offset);
        if (envs == null) return null;
        for (long i = 0; i < envs.getArraySize(); i++) {
            try {
                Value env = envs.getArrayElement(i);
                if (env.hasMember(name)) return env;
            } catch (org.graalvm.polyglot.PolyglotException e) {
                if (e.isInterrupted() || e.isCancelled()) return null;
                log("with: " + e.getMessage());
            }
        }
        return null;
    }

    private static Map<String, Object> location(String uri, Doc doc, int pos, int length) {
        return obj("uri", Bytes.fromJava(uri), "range", range(doc, pos, pos + length));
    }

    /** The file of a path literal: relative to the document, a directory's default.nix. */
    private static Path pathFile(String uri, String text) {
        try {
            Path base = Path.of(URI.create(uri)).getParent();
            Path p = text.startsWith("~/") ? Path.of(System.getProperty("user.home"), text.substring(2))
                    : text.startsWith("/") ? Path.of(text) : base.resolve(text);
            p = p.normalize();
            if (Files.isDirectory(p) && Files.exists(p.resolve("default.nix"))) p = p.resolve("default.nix");
            return Files.exists(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The definition and the uses of the variable at the offset (written, read). */
    private Object highlight(Doc doc, int offset) {
        Scopes.Use u = useAt(doc, offset);
        Scopes.Def def = u != null ? u.def() : defAt(doc, offset);
        if (def == null) return null;
        List<Object> out = new ArrayList<>();
        out.add(obj("range", range(doc, def.pos(), def.pos() + def.name().length()), "kind", 3L));
        for (Scopes.Use x : doc.scopes.uses) if (x.def() == def) out.add(obj("range", range(doc, x.pos(), x.pos() + x.name().length()), "kind", 2L));
        return out;
    }

    /** What renaming the variable at the offset would change, or why it can't be. */
    private record Renaming(Scopes.Def def, List<int[]> ranges, String refusal) {}

    private static Renaming renaming(Doc doc, int offset) {
        Scopes.Use u = useAt(doc, offset);
        Scopes.Def def = u != null ? u.def() : defAt(doc, offset);
        if (def == null) return new Renaming(null, null, u != null && u.kind() == Scopes.Kind.WITH ? "it comes from a `with`" : "not a variable");
        if (!def.renamable()) {
            return new Renaming(def, null, def.kind().equals("argument") ? "it's an attribute of the function's argument"
                    : def.kind().equals("rec") ? "it's an attribute of the set" : "it's inherited");
        }
        List<int[]> ranges = new ArrayList<>();
        ranges.add(new int[] {def.pos(), def.pos() + def.name().length()});
        for (Scopes.Use x : doc.scopes.uses) {
            if (x.def() != def) continue;
            if (x.inherited()) return new Renaming(def, null, "it's inherited by `inherit " + def.name() + ";`");
            ranges.add(new int[] {x.pos(), x.pos() + x.name().length()});
        }
        return new Renaming(def, ranges, null);
    }

    private Object prepareRename(Doc doc, int offset) {
        Renaming r = renaming(doc, offset);
        if (r.refusal != null) throw new Refused("can't rename this: " + r.refusal);
        return obj("range", range(doc, r.def.pos(), r.def.pos() + r.def.name().length()), "placeholder", Bytes.fromJava(r.def.name()));
    }

    private Object rename(Doc doc, int offset, String newName) {
        Renaming r = renaming(doc, offset);
        if (r.refusal != null) throw new Refused("can't rename this: " + r.refusal);
        if (!newName.matches("[A-Za-z_][A-Za-z0-9_'-]*") || KEYWORDS.contains(newName)) throw new Refused("not a name: " + newName);
        List<Object> edits = new ArrayList<>();
        for (int[] range : r.ranges) edits.add(obj("range", range(doc, range[0], range[1]), "newText", Bytes.fromJava(newName)));
        return obj("changes", obj(Bytes.fromJava(doc.uri), edits));
    }

    /** A request that can't be done, for the reason in its message (an LSP RequestFailed). */
    private static final class Refused extends RuntimeException {
        Refused(String message) {
            super(message);
        }
    }

    private Object references(Doc doc, int offset) {
        Scopes.Use u = useAt(doc, offset);
        Scopes.Def def = u != null ? u.def() : defAt(doc, offset);
        if (def == null) return null;
        List<Object> out = new ArrayList<>();
        out.add(location(doc.uri, doc, def.pos(), def.name().length()));
        for (Scopes.Use x : doc.scopes.uses) if (x.def() == def) out.add(location(doc.uri, doc, x.pos(), x.name().length()));
        return out;
    }

    /** A name in an {@code inherit (e) a b;} list, in a set or a let: (its start and end, where e starts), or null. */
    private static int[] inheritedNameAt(Doc doc, int offset) {
        int start = offset, end = offset;
        while (start > 0 && isNameChar(doc.text.charAt(start - 1))) start--;
        while (end < doc.text.length() && isNameChar(doc.text.charAt(end))) end++;
        if (start == end) return null;
        int from = inheritFromAt(doc.text, start);
        return from < 0 ? null : new int[] {start, end, from};
    }

    private Object hover(Doc doc, int offset) {
        int[] named = inheritedNameAt(doc, offset);
        if (named != null) {
            String name = doc.text.substring(named[0], named[1]);
            Value from = evaluateFrom(doc, named[0], named[0], named[2]);
            String md = from == null ? null : describeMarkdown(from, name, "`" + name + "` (inherited)");
            if (md != null) return obj("contents", obj("kind", "markdown", "value", Bytes.fromJava(md + footer(doc))), "range", range(doc, named[0], named[1]));
        }
        Object option = hoverOption(doc, offset);
        if (option != null) return option;
        Object attr = hoverAttribute(doc, offset);
        if (attr != null) return attr;
        Scopes.Use u = useAt(doc, offset);
        Scopes.Def inherited = u != null && u.def() != null ? u.def() : u == null ? defAt(doc, offset) : null;
        if (inherited != null && inherited.inheritedFrom()) {
            int at = u != null ? u.pos() : inherited.pos();
            Value from = evaluateFrom(doc, at, at, inherited.fromPos());
            String md = from == null ? null : describeMarkdown(from, inherited.name(), "`" + inherited.name() + "` (inherited)");
            if (md != null) return obj("contents", obj("kind", "markdown", "value", Bytes.fromJava(md + footer(doc))), "range", range(doc, at, at + inherited.name().length()));
        }
        Scopes.Def formal = u != null ? u.def() : defAt(doc, offset);
        Value source = formalSource(doc, formal);
        if (source != null) {
            int at = u != null ? u.pos() : formal.pos();
            String md = describeMarkdown(source, formal.name(), "`" + formal.name() + "` (argument)");
            if (md != null) return obj("contents", obj("kind", "markdown", "value", Bytes.fromJava(md + footer(doc))), "range", range(doc, at, at + formal.name().length()));
        }
        if (u == null) return null;
        if (u.kind() == Scopes.Kind.WITH) {
            Value env = withDefining(doc, u.pos(), u.name());
            if (env != null) {
                Object md = describeMarkdown(env, u.name(), "`" + u.name() + "` (from `with`)");
                if (md != null) return obj("contents", obj("kind", "markdown", "value", Bytes.fromJava(md + footer(doc))), "range", range(doc, u.pos(), u.pos() + u.name().length()));
            }
        }
        String text = switch (u.kind()) {
            case LOCAL -> "`" + u.name() + "`: " + (u.def().kind().equals("argument") ? "function argument" : u.def().kind().equals("rec") ? "attribute of a recursive set" : "let binding")
                    + ", line " + (doc.lines.line(u.def().pos()) + 1);
            case GLOBAL -> BuiltinDocs.doc(u.name()) != null
                    ? "`" + BuiltinDocs.signature(u.name()) + "`: built in\n\n" + BuiltinDocs.doc(u.name())
                    : "`" + u.name() + "`: built in" + (builtinNames.contains(u.name()) ? " (`builtins." + u.name() + "`)" : "");
            case WITH -> "`" + u.name() + "`: from `with`";
            case UNDEFINED -> "`" + u.name() + "`: undefined";
        };
        return obj("contents", obj("kind", "markdown", "value", Bytes.fromJava(text)), "range", range(doc, u.pos(), u.pos() + u.name().length()));
    }

    /** Hover on an attribute in a dotted path ({@code hello} in {@code pkgs.hello.meta}): its value, evaluated. */
    private Object hoverAttribute(Doc doc, int offset) {
        String text = doc.text;
        int start = offset, end = offset;
        while (start > 0 && isNameChar(text.charAt(start - 1))) start--;
        while (end < text.length() && isNameChar(text.charAt(end))) end++;
        if (start == end || start == 0 || text.charAt(start - 1) != '.') return null;
        String parent = pathBefore(text, start - 1);
        if (parent.isEmpty()) return null;
        String attr = text.substring(start, end);
        if (parent.equals("builtins") && BuiltinDocs.doc(attr) != null) {
            return obj("contents", obj("kind", "markdown", "value", Bytes.fromJava("`builtins." + BuiltinDocs.signature(attr) + "`\n\n" + BuiltinDocs.doc(attr))), "range", range(doc, start, end));
        }
        Value p = evaluate(doc, start, start, parent);
        if (p == null) return null;
        String name = text.substring(start, end);
        Object md = describeMarkdown(p, name, "`" + parent + "." + name + "`");
        return md == null ? null : obj("contents", obj("kind", "markdown", "value", Bytes.fromJava(md + footer(doc))), "range", range(doc, start, end));
    }

    /** Hover markdown for {@code parent.name}: what it is, and its documentation; or null. */
    private String describeMarkdown(Value parent, String name, String title) {
        try {
            Value d = describe().execute(parent, name);
            String md = title + ": " + d.getMember("kind").asString()
                    + (d.getMember("detail").isNull() ? "" : " `" + d.getMember("detail").asString() + "`");
            String docs = documentation(d);
            return docs.isEmpty() ? md : md + "\n\n" + docs;
        } catch (org.graalvm.polyglot.PolyglotException e) {
            log("describing " + name + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '-';
    }

    private Object completion(Doc doc, int offset) {
        String text = doc.text;
        int start = offset;
        while (start > 0 && isNameChar(text.charAt(start - 1))) start--;
        String prefix = text.substring(start, offset);
        List<Object> items = new ArrayList<>();
        int fromPos = inheritFromAt(text, start);
        if (fromPos >= 0) {
            Value from = evaluateFrom(doc, start, offset, fromPos);
            if (from == null || !from.hasMembers()) return items;
            for (String n : from.getMemberKeys()) {
                if (!n.startsWith(prefix)) continue;
                if (items.size() >= 500) return obj("isIncomplete", true, "items", items);
                items.add(obj("label", Bytes.fromJava(n), "kind", 5L,
                        "data", obj("uri", Bytes.fromJava(doc.uri), "offset", (long) start, "fromPos", (long) fromPos, "name", Bytes.fromJava(n))));
            }
            return items;
        }
        List<Object> args = callArgumentItems(doc, start, offset, prefix);
        if (args != null) return args;
        List<Object> values = optionValueItems(doc, start, offset, prefix);
        if (values != null) return values;
        List<String> optionPath = optionPath(doc, start, offset);
        if (optionPath != null) {
            int eol = text.indexOf('\n', offset);
            boolean restOfLineEmpty = text.substring(offset, eol < 0 ? text.length() : eol).isBlank();
            return optionItems(doc, optionPath, prefix, snippets && restOfLineEmpty);
        }
        if (start > 0 && text.charAt(start - 1) == '.') {
            // An attribute of what the dotted path before it is, evaluated.
            String expression = pathBefore(text, start - 1);
            if (expression.isEmpty()) return items;
            Value v = evaluate(doc, start, offset, expression);
            if (v == null || !v.hasMembers()) return items;
            boolean incomplete = false;
            for (String n : v.getMemberKeys()) {
                if (!n.startsWith(prefix)) continue;
                if (items.size() >= 500) {
                    incomplete = true;
                    break;
                }
                Map<String, Object> it = obj("label", Bytes.fromJava(n), "kind", 5L,
                        "data", obj("uri", Bytes.fromJava(doc.uri), "offset", (long) start, "expression", Bytes.fromJava(expression), "name", Bytes.fromJava(n)));
                items.add(it);
            }
            return obj("isIncomplete", incomplete, "items", items);
        }
        Set<String> seen = new HashSet<>();
        Scopes.Scope scope = scopeAt(doc, offset, start);
        if (scope != null) {
            for (Scopes.Def d : scope.names()) {
                if (d.name().startsWith(prefix) && seen.add(d.name())) items.add(item(d.name(), 6, d.kind().equals("argument") ? "argument" : d.kind()));
            }
        }
        for (String g : globals) {
            if (g.startsWith(prefix) && (!g.startsWith("__") || prefix.startsWith("_")) && seen.add(g)) items.add(builtinItem(g, g.equals("builtins") ? 9 : 3));
        }
        for (String k : KEYWORDS) if (k.startsWith(prefix)) items.add(item(k, 14, "keyword"));
        // From the `with`s around (after what's in scope, which wins over them): `with pkgs; [ hel`.
        boolean incomplete = false;
        if (scope != null && scope.with()) {
            Value envs = withEnvs(doc, start, offset);
            for (long i = 0; envs != null && i < envs.getArraySize() && !incomplete; i++) {
                try {
                    Value env = envs.getArrayElement(i);
                    if (!env.hasMembers()) continue;
                    for (String n : env.getMemberKeys()) {
                        if (!n.startsWith(prefix) || !seen.add(n)) continue;
                        if (items.size() >= 500) {
                            incomplete = true;
                            break;
                        }
                        items.add(obj("label", Bytes.fromJava(n), "kind", 5L, "detail", "with",
                                "data", obj("uri", Bytes.fromJava(doc.uri), "offset", (long) start, "with", i, "name", Bytes.fromJava(n))));
                    }
                } catch (org.graalvm.polyglot.PolyglotException e) {
                    if (e.isInterrupted() || e.isCancelled()) break;
                    log("with: " + e.getMessage());
                }
            }
        }
        return incomplete ? obj("isIncomplete", true, "items", items) : items;
    }

    /** The scope at an offset (see {@link #parseable}). */
    private Scopes.Scope scopeAt(Doc doc, int offset, int wordStart) {
        Parseable p = parseable(doc, wordStart, offset);
        return p == null ? null : Scopes.at(p.text, p.root, p.offset);
    }

    /** A text that parses, and the offset in it that stands for the cursor. */
    private record Parseable(String text, Expr root, int offset) {}

    /**
     * The text as it is, or with a name where the word at the cursor is (`pkgs.`, a binding
     * without its `;`), or else the last text that parsed.
     */
    private static Parseable parseable(Doc doc, int wordStart, int offset) {
        if (doc.root != null) return new Parseable(doc.text, doc.root, offset);
        for (String filler : List.of("x", "x;", "x; }", "x }", "x; in x", "x ]", "x)", "x = null;", "x = null; }")) {
            String patched = doc.text.substring(0, wordStart) + filler + doc.text.substring(offset);
            try {
                return new Parseable(patched, parse(doc.uri, patched), wordStart);
            } catch (Parser.SyntaxError e) {
                // try the next
            }
        }
        if (doc.goodRoot == null) return null;
        return new Parseable(doc.goodText, doc.goodRoot, Math.min(wordStart, doc.goodText.length()));
    }

    /** The dotted path that ends at {@code end} ({@code pkgs.python3Packages} before {@code .req}). */
    private static String pathBefore(String text, int end) {
        int s = end;
        while (s > 0 && (isNameChar(text.charAt(s - 1)) || text.charAt(s - 1) == '.')) s--;
        String path = text.substring(s, end);
        return path.isEmpty() || path.startsWith(".") || path.endsWith(".") || !Character.isLetter(path.charAt(0)) && path.charAt(0) != '_' ? "" : path;
    }

    // ------------------------------------------------------------ calls' argument sets

    /**
     * At a name in a set that a call gets ({@code fetchFromGitHub { ow }}): the function's
     * arguments that the set hasn't yet (required ones first), or null (not there, or a function
     * without named arguments).
     */
    private List<Object> callArgumentItems(Doc doc, int wordStart, int offset, String prefix) {
        String text = doc.text;
        int b = significantBefore(text, wordStart);
        if (b == 0 || text.charAt(b - 1) != '{' && text.charAt(b - 1) != ';') return null;
        String patched = text.substring(0, wordStart) + "x = null;" + text.substring(offset);
        Expr root;
        try {
            root = parse(doc.uri, patched);
        } catch (Parser.SyntaxError e) {
            return null;
        }
        Object[] call = callAround(root, null, wordStart);
        if (call == null) return null;
        Expr.App app = (Expr.App) call[0];
        Expr.Attrs set = (Expr.Attrs) call[2];
        Value fn;
        try {
            if (lspEval == null) lspEval = context.eval("nix", "__nixTruffle.lspEval");
            Map<String, Object> request = obj("file", Bytes.fromJava(Path.of(URI.create(doc.uri)).toString()), "text", Bytes.fromJava(patched),
                    "offset", (long) patched.substring(0, app.pos()).getBytes(StandardCharsets.UTF_8).length, "argument", (long) (int) call[1]);
            fn = lspEval.execute(Json.write(request).getBytes(StandardCharsets.ISO_8859_1), resolver(doc));
        } catch (org.graalvm.polyglot.PolyglotException e) {
            if (e.isInterrupted() || e.isCancelled()) throw e;
            log("the function called: " + e.getMessage());
            return null;
        }
        Value formals = context.eval("nix", """
                f: if builtins.isFunction f then builtins.functionArgs f
                   else if builtins.isAttrs f && f ? __functor then f.__functionArgs or (builtins.functionArgs (f.__functor f))
                   else { }""").execute(fn);
        if (!formals.hasMembers() || formals.getMemberKeys().isEmpty()) return null;
        Set<String> set_ = new HashSet<>();
        for (Expr.Binding bd : set.bindings()) {
            if (bd instanceof Expr.Binding.Assign a && a.path().getFirst().name() != null && a.pos() != wordStart) set_.add(a.path().getFirst().name());
            if (bd instanceof Expr.Binding.Inherit in) set_.addAll(in.names());
        }
        List<Object> items = new ArrayList<>();
        for (String n : formals.getMemberKeys()) {
            if (!n.startsWith(prefix) || set_.contains(n)) continue;
            boolean optional = formals.getMember(n).asBoolean();
            Map<String, Object> it = item(n, 10, optional ? "optional" : "required");
            it.put("sortText", Bytes.fromJava((optional ? "1" : "0") + n));
            items.add(it);
        }
        return items;
    }

    /**
     * The call whose argument the set at {@code offset} is, at a name in it: (the call, which
     * argument, the set), or null.
     */
    private static Object[] callAround(Expr e, Expr parent, int offset) {
        if (e instanceof Expr.Attrs a) {
            Expr.Binding last = null;
            for (Expr.Binding bd : a.bindings()) if (bd.pos() <= offset) last = bd;
            if (!(last instanceof Expr.Binding.Assign in) || offset < in.value().pos()) {
                if (parent instanceof Expr.App app) {
                    int i = app.args().indexOf(a);
                    if (i >= 0) return new Object[] {app, i, a};
                }
                return null;
            }
        }
        Expr in = null;
        for (Expr c : Scopes.children(e)) if (c.pos() <= offset) in = c;
        return in == null ? null : callAround(in, e, offset);
    }

    // ------------------------------------------------------------ option values

    /**
     * After {@code =} in a module ({@code mode = "|"}): the values the option's type allows
     * (an enum's, true and false, null), or null.
     */
    private List<Object> optionValueItems(Doc doc, int wordStart, int offset, String prefix) {
        String text = doc.text;
        int q = wordStart;
        boolean quoted = q > 0 && text.charAt(q - 1) == '"';
        if (quoted) q--;
        int eq = significantBefore(text, q);
        if (eq == 0 || text.charAt(eq - 1) != '=' || eq >= 2 && "=!<>".indexOf(text.charAt(eq - 2)) >= 0) return null;
        int keyEnd = significantBefore(text, eq - 1);
        int nameStart = keyEnd;
        while (nameStart > 0 && isNameChar(text.charAt(nameStart - 1))) nameStart--;
        if (nameStart == keyEnd) return null;
        List<String> path = optionPath(doc, nameStart, nameStart);
        if (path == null) return null;
        Value opts = optionsAt(doc, path);
        String name = text.substring(nameStart, keyEnd);
        if (opts == null || !opts.hasMember(name)) return null;
        Value values;
        try {
            values = context.eval("nix", """
                    o:
                    let
                      values = t:
                        let n = t.name or ""; in
                        if n == "enum" then t.functor.payload.values or t.functor.payload or [ ]
                        else if n == "bool" then [ true false ]
                        else if n == "nullOr" then values t.nestedTypes.elemType ++ [ null ]
                        else if n == "either" then values t.nestedTypes.left ++ values t.nestedTypes.right
                        else if builtins.elem n [ "uniq" "unique" ] then values t.nestedTypes.elemType
                        else [ ];
                    in
                    if (o._type or null) == "option" then map builtins.toJSON (values o.type) else [ ]""").execute(opts.getMember(name));
        } catch (org.graalvm.polyglot.PolyglotException e) {
            if (e.isInterrupted() || e.isCancelled()) throw e;
            return null;
        }
        List<Object> items = new ArrayList<>();
        for (long i = 0; i < values.getArraySize(); i++) {
            String v = values.getArrayElement(i).asString();
            boolean string = v.startsWith("\"");
            if (quoted && !string) continue;
            String label = quoted ? (String) Json.parse(Bytes.fromJava(v)) : v;
            if (quoted) label = Bytes.toJava(label);
            if (!label.startsWith(prefix)) continue;
            items.add(item(label, 12, String.join(".", path) + "." + name));
        }
        return items.isEmpty() ? null : items;
    }

    // ------------------------------------------------------------ NixOS options

    /**
     * At an attribute name in a NixOS module (a function of {@code { config, lib, pkgs, ... }}, or
     * {@code ...}): the option path before the word there, else null. It is the names of the sets
     * around it ({@code config}, {@code mkIf}, {@code mkMerge} and {@code let} are transparent),
     * and the dotted path typed before the word ({@code services.} in {@code services.ngi}).
     */
    private List<String> optionPath(Doc doc, int wordStart, int offset) {
        String text = doc.text;
        int ps = wordStart;
        while (ps > 0) {
            char ch = text.charAt(ps - 1);
            if (isNameChar(ch) || ch == '.') {
                ps--;
            } else if (ch == '"') {
                // a quoted name: "example.org"
                int q = text.lastIndexOf('"', ps - 2);
                if (q < 0) return null;
                ps = q;
            } else {
                break;
            }
        }
        int b = significantBefore(text, ps);
        if (b == 0 || text.charAt(b - 1) != '{' && text.charAt(b - 1) != ';') return null;
        List<String> typed = components(text.substring(ps, wordStart));
        if (typed == null) return null;
        // A binding where the cursor is, so that a set's empty line (after `enable = true;`) is
        // where a name goes, not in the value before it.
        Parseable p;
        String patched = text.substring(0, wordStart) + "x = null;" + text.substring(offset);
        try {
            p = new Parseable(patched, parse(doc.uri, patched), wordStart);
        } catch (Parser.SyntaxError e) {
            p = parseable(doc, wordStart, offset);
        }
        if (p == null) return null;
        List<String> around = enclosing(p.root, ps);
        if (around == null) return null;
        List<String> path = new ArrayList<>(around);
        path.addAll(typed);
        return path;
    }

    /**
     * Where the text before {@code pos} ends, without white space and comments ({@code #} to the
     * end of a line, {@code /* *}{@code /}).
     */
    private static int significantBefore(String text, int pos) {
        int b = pos;
        while (true) {
            while (b > 0 && Character.isWhitespace(text.charAt(b - 1))) b--;
            if (b >= 2 && text.startsWith("*/", b - 2)) {
                int open = text.lastIndexOf("/*", b - 2);
                if (open < 0) return b;
                b = open;
                continue;
            }
            // A line comment: a `#` on this line that isn't in a string (roughly: an even number of quotes before it).
            int lineStart = text.lastIndexOf('\n', Math.max(0, b - 1)) + 1;
            String line = text.substring(lineStart, b);
            int hash = -1;
            boolean inString = false;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '"' && (i == 0 || line.charAt(i - 1) != '\\')) inString = !inString;
                else if (ch == '#' && !inString) {
                    hash = i;
                    break;
                }
            }
            if (hash < 0) return b;
            b = lineStart + hash;
        }
    }

    /** A typed attribute path's names ({@code services."a.b".} is services, a.b), or null. */
    private static List<String> components(String typed) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < typed.length()) {
            int j;
            if (typed.charAt(i) == '"') {
                j = typed.indexOf('"', i + 1);
                if (j < 0) return null;
                out.add(typed.substring(i + 1, j));
                j++;
            } else {
                j = typed.indexOf('.', i);
                if (j < 0) return null;
                out.add(typed.substring(i, j));
            }
            if (j >= typed.length() || typed.charAt(j) != '.') return null;
            i = j + 1;
        }
        return out;
    }

    /** The option path of the sets around {@code offset} in a module, or null if it isn't in a module's config. */
    private static List<String> enclosing(Expr root, int offset) {
        if (!(root instanceof Expr.Lambda l) || l.formals() == null) return null;
        boolean module = l.formals().ellipsis() || l.formals().formals().stream().anyMatch(f -> List.of("config", "lib", "pkgs", "options").contains(f.name()));
        if (!module) return null;
        List<String> path = new ArrayList<>();
        Expr e = l.body();
        while (true) {
            if (e instanceof Expr.Attrs a) {
                // In the value of the last binding before the offset, or else at this set's level.
                Expr.Binding last = null;
                for (Expr.Binding bd : a.bindings()) if (bd.pos() <= offset) last = bd;
                if (!(last instanceof Expr.Binding.Assign in) || offset < in.value().pos()) break;
                for (Expr.AttrKey k : in.path()) {
                    if (k.name() == null) return null;
                    path.add(k.name());
                }
                e = in.value();
            } else {
                Expr next = null;
                if (e instanceof Expr.Lambda) return null;
                for (Expr c : Scopes.children(e)) if (c.pos() <= offset) next = c;
                if (next == null) break;
                e = next;
            }
        }
        if (!path.isEmpty() && path.getFirst().equals("config")) path.removeFirst();
        else if (!path.isEmpty() && List.of("options", "imports", "disabledModules").contains(path.getFirst())) return null;
        return path;
    }

    /** {@code helper resolver path}: the options under a path (into submodules), or null. */
    private Value optionsHelper;

    private Value optionsAt(Doc doc, List<String> path) {
        if (optionsHelper == null) {
            optionsHelper = context.eval("nix", """
                    resolver: pathJSON:
                    let
                      # (a host array isn't a Nix list: `== [ ]` is false for an empty one)
                      path = builtins.fromJSON pathJSON;
                      isOption = o: builtins.isAttrs o && (o._type or null) == "option";
                      at = opts: path:
                        if path == [ ] then opts
                        else let o = opts.${builtins.head path} or null; in
                        if o == null then null
                        else if isOption o then under o.type (builtins.tail path)
                        else if builtins.isAttrs o then at o (builtins.tail path)
                        else null;
                      # the options in a value of type t, after the names in path (an attrsOf's are skipped)
                      under = t: path:
                        let n = t.name or ""; in
                        if builtins.elem n [ "attrsOf" "lazyAttrsOf" ] then (if path == [ ] then null else under t.nestedTypes.elemType (builtins.tail path))
                        else if builtins.elem n [ "nullOr" "uniq" "unique" ] then under t.nestedTypes.elemType path
                        else if n == "submodule" then at (t.getSubOptions [ ]) path
                        else null;
                    in at (resolver { arg = "options"; }) path
                    """);
        }
        try {
            List<Object> p = path.stream().map(x -> (Object) Bytes.fromJava(x)).toList();
            Value v = optionsHelper.execute(resolver(doc), Bytes.toJava(Json.write(p)));
            return v.isNull() ? null : v;
        } catch (org.graalvm.polyglot.PolyglotException e) {
            log("options at " + path + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The options and sets of options under a path. With {@code snippet}, an option is inserted
     * as {@code name = ...;}, the cursor in its value (a choice of true/false, or of a short
     * enum's values).
     */
    private Object optionItems(Doc doc, List<String> path, String prefix, boolean snippet) {
        List<Object> items = new ArrayList<>();
        Value opts = optionsAt(doc, path);
        if (opts == null || !opts.hasMembers()) return items;
        List<String> names = opts.getMemberKeys().stream().filter(n -> n.startsWith(prefix) && !n.startsWith("_")).toList();
        for (String n : names) {
            Value o = opts.getMember(n);
            boolean option = o.hasMember("_type") && "option".equals(o.getMember("_type").isString() ? o.getMember("_type").asString() : null);
            Map<String, Object> it = obj("label", Bytes.fromJava(n), "kind", option ? 10L : 9L,
                    "data", obj("uri", Bytes.fromJava(doc.uri), "option", path.stream().map(x -> (Object) Bytes.fromJava(x)).toList(), "name", Bytes.fromJava(n)));
            if (option && snippet) {
                String value = names.size() <= 200 ? choice(o) : "$0";
                it.put("insertText", Bytes.fromJava(snippetEscape(n) + " = " + value + ";"));
                it.put("insertTextFormat", 2L);
            }
            items.add(it);
        }
        return items;
    }

    /** A snippet's value for an option: a choice of its type's few values, else the cursor. */
    private String choice(Value option) {
        try {
            Value vs = context.eval("nix", """
                    o:
                    let
                      unwrap = t: if builtins.elem (t.name or "") [ "nullOr" "uniq" "unique" ] then unwrap t.nestedTypes.elemType else t;
                      t = unwrap o.type;
                      n = t.name or "";
                      values = t.functor.payload.values or t.functor.payload or [ ];
                    in
                    if n == "bool" then [ "true" "false" ]
                    else if n == "enum" && builtins.all builtins.isString values && builtins.length values <= 20 then map builtins.toJSON values
                    else [ ]""").execute(option);
            if (vs.getArraySize() == 0) return "$0";
            boolean strings = vs.getArrayElement(0).asString().startsWith("\"");
            List<String> choices = new ArrayList<>();
            for (long i = 0; i < vs.getArraySize(); i++) {
                String v = vs.getArrayElement(i).asString();
                if (strings) v = Bytes.toJava((String) Json.parse(Bytes.fromJava(v)));
                choices.add(v.replace("\\", "\\\\").replace(",", "\\,").replace("|", "\\|").replace("$", "\\$").replace("}", "\\}"));
            }
            String c = "${1|" + String.join(",", choices) + "|}";
            return strings ? "\"" + c + "\"" : c;
        } catch (org.graalvm.polyglot.PolyglotException e) {
            if (e.isInterrupted() || e.isCancelled()) throw e;
            return "$0";
        }
    }

    private static String snippetEscape(String s) {
        return s.replace("\\", "\\\\").replace("$", "\\$").replace("}", "\\}");
    }

    /** Hover on an option's name in a module: its type, description and default. */
    private Object hoverOption(Doc doc, int offset) {
        String text = doc.text;
        int start = offset, end = offset;
        while (start > 0 && isNameChar(text.charAt(start - 1))) start--;
        while (end < text.length() && isNameChar(text.charAt(end))) end++;
        if (start == end) return null;
        List<String> path = optionPath(doc, start, start);
        if (path == null) return null;
        Value opts = optionsAt(doc, path);
        String name = text.substring(start, end);
        if (opts == null || !opts.hasMember(name)) return null;
        try {
            Value d = describe().execute(opts, name);
            String md = "`" + String.join(".", path) + (path.isEmpty() ? "" : ".") + name + "`: " + d.getMember("kind").asString()
                    + (d.getMember("detail").isNull() ? "" : " `" + d.getMember("detail").asString() + "`");
            String docs = documentation(d);
            if (!docs.isEmpty()) md += "\n\n" + docs;
            return obj("contents", obj("kind", "markdown", "value", Bytes.fromJava(md + footer(doc))), "range", range(doc, start, end));
        } catch (org.graalvm.polyglot.PolyglotException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ evaluation

    /** {@code expression} in the scope at the cursor, or null (with the reason in the client's log). */
    private Value evaluate(Doc doc, int wordStart, int offset, String expression) {
        Parseable p = parseable(doc, wordStart, offset);
        if (p == null) return null;
        try {
            if (lspEval == null) lspEval = context.eval("nix", "__nixTruffle.lspEval");
            String path = Path.of(URI.create(doc.uri)).toString();
            Map<String, Object> request = obj("file", Bytes.fromJava(path), "text", Bytes.fromJava(p.text),
                    "offset", (long) p.text.substring(0, p.offset).getBytes(StandardCharsets.UTF_8).length, "expression", Bytes.fromJava(expression));
            byte[] json = Json.write(request).getBytes(StandardCharsets.ISO_8859_1);
            try {
                return lspEval.execute(json, resolver(doc));
            } catch (org.graalvm.polyglot.PolyglotException e) {
                if (e.isInterrupted() || e.isCancelled()) throw e;
                // Without the guesses (no <nixpkgs>, say), for what doesn't need them.
                log("evaluating " + expression + ": " + e.getMessage() + "; again without arguments");
                if (noArguments == null) noArguments = context.eval("nix", """
                        request:
                          if request ? arg then throw "nix-truffle lsp: no value for '${request.arg}'"
                          else builtins.listToAttrs (map (name: { inherit name; value = throw "nix-truffle lsp: no value for '${name}'"; })
                            (builtins.filter (n: !builtins.elem n request.optional) request.names))
                        """);
                return lspEval.execute(json, noArguments);
            }
        } catch (org.graalvm.polyglot.PolyglotException | IllegalArgumentException e) {
            log("evaluating " + expression + ": " + e.getMessage());
            return null;
        }
    }

    private Value noArguments;

    /**
     * The file's own expression at {@code fromPos} ({@code lib} in {@code inherit (lib) mkIf;}), in
     * its scope; the cursor's word is where the text may be completed to parse.
     */
    private Value evaluateFrom(Doc doc, int wordStart, int offset, int fromPos) {
        Parseable p = parseable(doc, wordStart, offset);
        if (p == null || fromPos > p.text.length()) return null;
        try {
            if (lspEval == null) lspEval = context.eval("nix", "__nixTruffle.lspEval");
            Map<String, Object> request = obj("file", Bytes.fromJava(Path.of(URI.create(doc.uri)).toString()), "text", Bytes.fromJava(p.text),
                    "offset", (long) p.text.substring(0, fromPos).getBytes(StandardCharsets.UTF_8).length);
            return lspEval.execute(Json.write(request).getBytes(StandardCharsets.ISO_8859_1), resolver(doc));
        } catch (org.graalvm.polyglot.PolyglotException | IllegalArgumentException e) {
            if (e instanceof org.graalvm.polyglot.PolyglotException pe && (pe.isInterrupted() || pe.isCancelled())) throw pe;
            log("evaluating what is inherited from: " + e.getMessage());
            return null;
        }
    }

    /** At a name in {@code inherit (e) a b c}: where {@code e} starts, else -1. */
    private static int inheritFromAt(String text, int wordStart) {
        int kw = text.lastIndexOf("inherit", wordStart);
        if (kw < 0 || text.substring(kw, wordStart).indexOf(';') >= 0) return -1;
        if (kw > 0 && (Character.isLetterOrDigit(text.charAt(kw - 1)) || text.charAt(kw - 1) == '_')) return -1;
        int i = kw + "inherit".length();
        while (i < wordStart && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= wordStart || text.charAt(i) != '(') return -1;
        int depth = 0, close = -1;
        for (int j = i; j < wordStart; j++) {
            char c = text.charAt(j);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) {
                close = j;
                break;
            }
        }
        if (close < 0) return -1;
        int from = i + 1;
        while (from < close && Character.isWhitespace(text.charAt(from))) from++;
        return from;
    }

    // ------------------------------------------------------------ what a file is evaluated with

    /**
     * The session (one Nix value, made again after {@link #reload}): the workspace's flake, the
     * settings' scope ({@code flake}, {@code inputs}, {@code system}, {@code upstream}), the
     * package set, the NixOS configurations, what each imports, and the resolvers: what a file's
     * functions get for their arguments, given {@code { names, optional }} (formals) or
     * {@code { arg }}.
     */
    private static final String SESSION = """
            let
              system = builtins.currentSystem;
              flake = %s;
              inputs = if flake != null then flake.inputs else { };
              upstream = if inputs ? nixpkgs then inputs.nixpkgs.legacyPackages.${system} else import <nixpkgs> { };
              scope = { inherit flake inputs system upstream; };
              configured = %s;
              # The package set: the setting's, else the flake's own, else nixpkgs with its overlays,
              # else its nixpkgs, else <nixpkgs>.
              packages =
                if configured != null then configured
                else if flake != null && (flake.legacyPackages or { }) ? ${system} then { set = flake.legacyPackages.${system}; name = "legacyPackages.${system}"; }
                else if flake != null && flake ? overlays && inputs ? nixpkgs then
                  { set = import inputs.nixpkgs { inherit system; overlays = builtins.attrValues flake.overlays; }; name = "inputs.nixpkgs with the flake's overlays"; }
                else if inputs ? nixpkgs then { set = upstream; name = "inputs.nixpkgs.legacyPackages.${system}"; }
                else { set = upstream; name = "<nixpkgs>"; };
              # Configurations without the module system's check that what is defined is declared:
              # one being edited often isn't, and its options would be an error.
              # (home-manager sets _module.check itself: lib.mkForce false)
              unchecked = c: if c != null && c ? extendModules
                then c.extendModules { modules = [ { _module.check = { _type = "override"; priority = 50; content = false; }; } ]; }
                else c;
              # NixOS's configurations, and home-manager's.
              raw = {
                nixos = if flake != null then flake.nixosConfigurations or { } else { };
                home = if flake != null then flake.homeConfigurations or { } else { };
              };
              configurations = builtins.mapAttrs (_: builtins.mapAttrs (_: unchecked)) raw;
            in {
              inherit scope configurations unchecked;
              packagesName = packages.name;
              configurationNames = builtins.mapAttrs (_: builtins.attrNames) configurations;
              flakePath = if flake != null then flake.outPath else null;
              # The arguments a file's function is called with, evaluating the flake's packages of
              # these names afresh (another evaluation may have called it already).
              calls = file: names: __nixTruffle.callsTo file (_:
                let
                  fresh = %s;
                  ps = ((fresh.legacyPackages or { }).${system} or { }) // ((fresh.packages or { }).${system} or { });
                in builtins.foldl' (acc: n: builtins.seq (if ps ? ${n} then ps.${n} else null) acc) null names);
              # A resolver that gives the file's function (at pos) the arguments it is called with.
              withCall = args: pos: base: request:
                if request ? names && (request.pos or (-1)) == pos then args
                else if request ? where && args ? ${request.where} then args
                else base request;
              # The files a configuration imports: listing its options imports all of its modules,
              # in an evaluation of its own (one done before imported them already).
              imports = kind: name: (__nixTruffle.importsDuring (_: builtins.attrNames (unchecked raw.${kind}.${name}).options)).files;
              # A NixOS module gets its configuration's pkgs; anything else the package set.
              resolver = nixos: module:
                let
                  pkgs = if module && nixos != null then nixos.pkgs or nixos._module.args.pkgs else packages.set;
                  # A module gets its configuration's module arguments (modulesPath, NixOS's utils,
                  # home-manager's osConfig, ...), and a configuration made by hand can say more
                  # (args).
                  special = { inherit pkgs; lib = pkgs.lib; }
                    // (if module && nixos != null then nixos._module.args or { } else { })
                    // (if nixos != null then nixos.args or { } // { inherit (nixos) config options; } else { })
                    // (if flake != null then inputs // { self = flake; inherit inputs; } else { });
                  # an overlay's plain arguments (final: prev:, self: super:)
                  plain = { final = pkgs; self = pkgs; prev = upstream; super = upstream; };
                  # a package of python3Packages, called with its names
                  nested = pkgs.python3Packages or { };
                  value = name: special.${name} or pkgs.${name} or nested.${name} or (throw "nix-truffle lsp: no value for '${name}'");
                in
                request:
                  # where a name comes from (for "go to definition"): the set it is in
                  if request ? where then
                    (if special ? ${request.where} then special else if pkgs ? ${request.where} then pkgs
                     else if nested ? ${request.where} then nested else null)
                  else if request ? arg then plain.${request.arg} or (value request.arg)
                  else builtins.listToAttrs (map (name: { inherit name; value = value name; })
                    (builtins.filter (n: special ? ${n} || pkgs ? ${n} || nested ? ${n} || !builtins.elem n request.optional) request.names));
            }
            """;

    private Value session;
    /** Each NixOS configuration's imported files (in the flake's store copy), once needed. */
    private Map<String, Set<String>> importsOf;
    private final Map<String, Value> resolverFor = new HashMap<>();
    /** What a document's last evaluation was with, for hover. */
    private final Map<String, String> usedFor = new HashMap<>();

    private Value session() {
        if (session == null) {
            String flake = rootPath != null && Files.exists(Path.of(rootPath, "flake.nix")) ? "builtins.getFlake " + nixString("path:" + rootPath) : "null";
            String nixpkgs = setting("nixpkgs");
            String configured = nixpkgs == null ? "null" : "{ set = with scope; (" + nixpkgs + "); name = " + nixString(nixpkgs) + "; }";
            session = context.eval("nix", SESSION.formatted(flake, configured, flake));
        }
        return session;
    }

    /** A setting (a string), or null. */
    private String setting(String name) {
        return initOptions.get(name) instanceof String v ? Bytes.toJava(v) : null;
    }

    /** A setting's expression, in its scope ({@code flake}, {@code inputs}, {@code system}, {@code upstream}). */
    private Value inScope(String expression) {
        return context.eval("nix", "session: with session.scope; (" + expression + ")").execute(session());
    }

    private Value unchecked(Value configuration) {
        return session().getMember("unchecked").execute(configuration);
    }

    /** What a document is evaluated with: a NixOS configuration (or null), and whether it is a module. */
    private record Selection(String key, Value nixos, boolean module, String label) {}

    private Value resolver(Doc doc) {
        Selection sel = select(doc);
        usedFor.put(doc.uri, sel.label);
        Value r = resolverFor.get(sel.key);
        if (r == null) {
            r = session().getMember("resolver").execute(sel.nixos, sel.module);
            resolverFor.put(sel.key, r);
        }
        if (!sel.module) {
            Call call = callOf(doc);
            if (call != null) {
                usedFor.put(doc.uri, "the arguments `" + call.label + "` calls it with");
                return session().getMember("withCall").execute(call.args, call.pos, r);
            }
        }
        return r;
    }

    /** The arguments a file's function is called with (in the flake's packages), and where it is. */
    private record Call(Value args, long pos, String label) {}

    private final Map<String, java.util.Optional<Call>> callsOf = new HashMap<>();

    /**
     * For a function's file in the workspace's flake: the arguments its function is called
     * with when the flake's package named after its directory or itself is evaluated, or null.
     */
    private Call callOf(Doc doc) {
        String rel = relative(doc);
        Expr root = treeOf(doc);
        if (rel == null || !(root instanceof Expr.Lambda l) || l.formals() == null) return null;
        java.util.Optional<Call> known = callsOf.get(rel);
        if (known != null) return known.orElse(null);
        Call call = null;
        try {
            Value flakePath = session().getMember("flakePath");
            if (!flakePath.isNull()) {
                Path p = Path.of(rel);
                String base = p.getFileName().toString().replaceFirst("\\.nix$", "");
                List<String> names = new ArrayList<>();
                if (p.getParent() != null) names.add(p.getParent().getFileName().toString());
                if (!base.equals("default") && !base.equals("package")) names.add(base);
                long t = System.nanoTime();
                Value calls = session().getMember("calls").execute(flakePath.asString() + "/" + rel, context.eval("nix", "builtins.fromJSON").execute(Bytes.toJava(Json.write(names.stream().map(n -> (Object) Bytes.fromJava(n)).toList()))));
                log("the calls of " + rel + ": " + calls.getArraySize() + " in " + (System.nanoTime() - t) / 1_000_000 + " ms");
                if (calls.getArraySize() > 0) {
                    String text = doc.root != null ? doc.text : doc.goodText;
                    long pos = text == null ? 0 : text.substring(0, Math.min(l.pos(), text.length())).getBytes(StandardCharsets.UTF_8).length;
                    call = new Call(calls.getArrayElement(0), pos, "packages." + session().getMember("scope").getMember("system").asString() + "." + names.getFirst());
                }
            }
        } catch (org.graalvm.polyglot.PolyglotException e) {
            if (e.isInterrupted() || e.isCancelled()) throw e;
            log("the calls of " + rel + ": " + e.getMessage());
        }
        callsOf.put(rel, java.util.Optional.ofNullable(call));
        return call;
    }

    /**
     * The configuration for a document (NixOS's or home-manager's): a per-path setting
     * ({@code configurations}: glob to expression); else, of the kind the configurations that
     * import it are (or its path says: home-manager's if it has `home-manager` or `home.nix`
     * in it), the setting for that kind ({@code nixos}, {@code home}), else one of those that
     * import it (one named in its path first), else one named in its path, else the first.
     */
    private Selection select(Doc doc) {
        String rel = relative(doc);
        boolean module = moduleLike(doc);
        if (initOptions.get("configurations") instanceof Map<?, ?> m && rel != null) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String glob = Bytes.toJava((String) e.getKey());
                if (java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob).matches(Path.of(rel)) && e.getValue() instanceof String expr) {
                    return selection("expr:" + Bytes.toJava(expr), unchecked(inScope(Bytes.toJava(expr))), module, "the `configurations` setting for `" + glob + "`");
                }
            }
        }
        Map<String, List<String>> names = new LinkedHashMap<>();
        try {
            Value ns = session().getMember("configurationNames");
            for (String kind : List.of("nixos", "home")) {
                List<String> list = new ArrayList<>();
                Value kindNames = ns.getMember(kind);
                for (long i = 0; i < kindNames.getArraySize(); i++) list.add(kindNames.getArrayElement(i).asString());
                names.put(kind, list);
            }
        } catch (org.graalvm.polyglot.PolyglotException e) {
            if (e.isInterrupted() || e.isCancelled()) throw e;
            log("configurations: " + e.getMessage());
            names.put("nixos", List.of());
            names.put("home", List.of());
        }
        int count = names.get("nixos").size() + names.get("home").size();
        List<String> importing = count > 1 && rel != null && (module || importsOf != null) ? importing(rel) : List.of();
        if (!importing.isEmpty()) module = true;
        String kind = !importing.isEmpty() ? importing.getFirst().substring(0, importing.getFirst().indexOf(':'))
                : names.get("nixos").isEmpty() && !names.get("home").isEmpty() || rel != null && (rel.contains("home-manager") || rel.endsWith("home.nix")) && !names.get("home").isEmpty() ? "home"
                : "nixos";
        String setting = setting(kind);
        if (setting != null) return selection("expr:" + setting, unchecked(inScope(setting)), module, "the `" + kind + "` setting");
        List<String> kindNames = names.get(kind);
        if (kindNames.isEmpty()) return selection("none", null, module, null);
        List<String> named = new ArrayList<>();
        for (Path p = Path.of(URI.create(doc.uri)).getParent(); p != null; p = p.getParent()) {
            if (p.getFileName() != null && kindNames.contains(p.getFileName().toString())) named.add(p.getFileName().toString());
        }
        List<String> imported = importing.stream().filter(k -> k.startsWith(kind + ":")).map(k -> k.substring(kind.length() + 1)).toList();
        String name = !imported.isEmpty() ? imported.stream().filter(named::contains).findFirst().orElse(imported.getFirst())
                : !named.isEmpty() ? named.getFirst() : kindNames.getFirst();
        Value configuration = session().getMember("configurations").getMember(kind).getMember(name);
        String label = "`" + (kind.equals("home") ? "homeConfigurations." : "nixosConfigurations.") + name + "`";
        return selection(kind + ":" + name, configuration, module, label);
    }

    private Selection selection(String key, Value nixos, boolean module, String configuration) {
        String packages = session().getMember("packagesName").asString();
        String label = module && configuration != null ? configuration + " (its `pkgs`)" : "`" + packages + "`";
        return new Selection(key + (module ? ":module" : ""), nixos, module, label);
    }

    /** The configurations ({@code nixos:name}, {@code home:name}) that import the workspace file {@code rel}. */
    private List<String> importing(String rel) {
        if (importsOf == null) {
            long t = System.nanoTime();
            importsOf = new LinkedHashMap<>();
            Value all = session().getMember("configurationNames");
            List<String> keys = new ArrayList<>();
            for (String kind : List.of("nixos", "home")) {
                Value names = all.getMember(kind);
                for (long i = 0; i < names.getArraySize(); i++) keys.add(kind + ":" + names.getArrayElement(i).asString());
            }
            for (String n : keys) {
                Set<String> files = new HashSet<>();
                try {
                    Value fs = session().getMember("imports").execute(n.substring(0, n.indexOf(':')), n.substring(n.indexOf(':') + 1));
                    for (long j = 0; j < fs.getArraySize(); j++) files.add(fs.getArrayElement(j).asString());
                } catch (org.graalvm.polyglot.PolyglotException e) {
                    if (e.isInterrupted() || e.isCancelled()) {
                        importsOf = null;
                        throw e;
                    }
                    log("the imports of " + n + ": " + e.getMessage());
                }
                importsOf.put(n, files);
            }
            log("what the configurations import: " + importsOf.size() + " in " + (System.nanoTime() - t) / 1_000_000 + " ms");
        }
        Value flakePath = session().getMember("flakePath");
        if (flakePath.isNull()) return List.of();
        String file = flakePath.asString() + "/" + rel;
        List<String> out = new ArrayList<>();
        importsOf.forEach((n, files) -> {
            if (files.contains(file)) out.add(n);
        });
        return out;
    }

    /** The document's path in the workspace, or null. */
    private String relative(Doc doc) {
        if (rootPath == null) return null;
        try {
            Path p = Path.of(URI.create(doc.uri)), root = Path.of(rootPath);
            return p.startsWith(root) ? root.relativize(p).toString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether a document looks like a NixOS module: a function of config, options or modulesPath, or of pkgs and `...`. */
    private static boolean moduleLike(Doc doc) {
        Expr root = treeOf(doc);
        if (!(root instanceof Expr.Lambda l) || l.formals() == null) return false;
        List<String> names = l.formals().formals().stream().map(Expr.Formal::name).toList();
        return names.contains("config") || names.contains("options") || names.contains("modulesPath") || l.formals().ellipsis() && names.contains("pkgs");
    }

    /**
     * Forgets everything evaluated, in a new context (the old one's caches would have the files,
     * fetched flakes and copies to the store as they were): the flake, the configurations and
     * what they import are evaluated again when needed.
     */
    private void reload() {
        session = null;
        importsOf = null;
        callsOf.clear();
        resolverFor.clear();
        usedFor.clear();
        lspEval = null;
        describe = null;
        locate = null;
        optionsHelper = null;
        noArguments = null;
        Context old = context;
        context = contexts.get();
        old.close(true);
    }

    /** The document's tree: as it is, else repaired, else the last that parsed. */
    private static Expr treeOf(Doc doc) {
        return doc.root != null ? doc.root : doc.repair != null ? doc.repair.root : doc.goodRoot;
    }

    /** A hover's note of what it was evaluated with: what a file's function got (if it is one). */
    private String footer(Doc doc) {
        String used = usedFor.get(doc.uri);
        Expr root = treeOf(doc);
        return used == null || !(root instanceof Expr.Lambda) ? "" : "\n\n---\n*evaluated with " + used + "*";
    }

    private static String nixString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("${", "\\${") + "\"";
    }

    /** {@code { kind, detail, doc, position }} of a value (an option, a package, a function, ...). */
    private Value describe() {
        if (describe == null) {
            describe = context.eval("nix", """
                    parent: name:
                    let
                      safe = x: let r = builtins.tryEval x; in if r.success then r.value else null;
                      v = parent.${name};
                      t = builtins.typeOf v;
                      text = d: if builtins.isString d then d else d.text or "";
                      option = t == "set" && (v._type or null) == "option";
                      drv = t == "set" && (v.type or null) == "derivation";
                      # a function made overridable (fetchFromGitHub): a set with __functor
                      functor = t == "set" && !drv && v ? __functor;
                    in {
                      kind = if option then "option" else if drv then "package" else if t == "lambda" || functor then "function" else t;
                      detail = safe (if option then v.type.description or "option"
                        else if drv then v.name or "derivation"
                        else if t == "lambda" || functor then
                          let args = if functor then v.__functionArgs or { } else builtins.functionArgs v; in
                          if args == { } then "function"
                          else "{ " + builtins.concatStringsSep ", " (map (n: if args.${n} then n + " ? ..." else n) (builtins.attrNames args)) + " }: ..."
                        else if t == "set" then "attribute set"
                        else if builtins.elem t [ "int" "float" "bool" "null" ] then builtins.toJSON v
                        else if t == "string" then builtins.toJSON (builtins.substring 0 80 v)
                        else t);
                      doc = safe (if option then text (v.description or "")
                        else if drv then
                          let
                            m = v.meta or { };
                            licenses = map (l: l.spdxId or l.shortName or "?") (if builtins.isList (m.license or [ ]) then m.license or [ ] else [ m.license ]);
                            links = builtins.filter (x: x != "") [
                              (if m ? homepage then "[homepage](" + toString m.homepage + ")" else "")
                              (if licenses != [ ] then "license: " + builtins.concatStringsSep ", " licenses else "")
                            ];
                          in builtins.concatStringsSep "\n\n" (builtins.filter (x: x != "") [
                            (m.description or "")
                            (m.longDescription or "")
                            (builtins.concatStringsSep " · " links)
                          ])
                        else "");
                      default = if option then safe (if v ? defaultText then text v.defaultText else builtins.toJSON v.default) else null;
                      # a function's own position, where its doc comment is (lib's are inherited
                      # into lib from where they're defined)
                      inherit name;
                      attrPos = safe (builtins.unsafeGetAttrPos name parent);
                      lambdaPos = if t == "lambda" then safe (__nixTruffle.lambdaPos v) else null;
                    }
                    """);
        }
        return describe;
    }

    /** A completion item's details: what its value is, and its doc comment. */
    private Object resolveItem(Map<String, Object> item) {
        if (!(item.get("data") instanceof Map<?, ?>)) return item;
        Map<String, Object> data = Json.obj(item.get("data"));
        Doc doc = docs.get(Bytes.toJava(Json.str(data.get("uri"))));
        if (doc == null) return item;
        Value parent;
        if (data.get("fromPos") instanceof Number from) {
            int offset = (int) Math.min(((Number) data.get("offset")).longValue(), doc.text.length());
            parent = evaluateFrom(doc, offset, offset, from.intValue());
        } else if (data.get("with") instanceof Number w) {
            int offset = (int) Math.min(((Number) data.get("offset")).longValue(), doc.text.length());
            Value envs = withEnvs(doc, offset, offset);
            parent = envs == null || w.longValue() >= envs.getArraySize() ? null : envs.getArrayElement(w.longValue());
        } else if (data.get("option") instanceof List<?> l) {
            parent = optionsAt(doc, l.stream().map(x -> Bytes.toJava((String) x)).toList());
        } else {
            int offset = (int) Math.min(((Number) data.get("offset")).longValue(), doc.text.length());
            parent = evaluate(doc, offset, offset, Bytes.toJava(Json.str(data.get("expression"))));
        }
        if (parent == null) return item;
        Map<String, Object> out = new LinkedHashMap<>(item);
        String itemName = Bytes.toJava(Json.str(data.get("name")));
        if ("builtins".equals(data.get("expression") instanceof String e ? Bytes.toJava(e) : null) && BuiltinDocs.doc(itemName) != null) {
            out.put("detail", Bytes.fromJava(BuiltinDocs.signature(itemName)));
            out.put("documentation", obj("kind", "markdown", "value", Bytes.fromJava(BuiltinDocs.doc(itemName))));
            return out;
        }
        try {
            Value d = describe().execute(parent, Bytes.toJava(Json.str(data.get("name"))));
            out.put("detail", Bytes.fromJava(d.getMember("detail").isNull() ? "" : d.getMember("detail").asString()));
            String md = documentation(d);
            if (!md.isEmpty()) out.put("documentation", obj("kind", "markdown", "value", Bytes.fromJava(md)));
        } catch (org.graalvm.polyglot.PolyglotException e) {
            log("describing " + data.get("name") + ": " + e.getMessage());
        }
        return out;
    }

    /** Markdown for a description: its doc (or the doc comment before its definition), and default. */
    private static String documentation(Value d) {
        StringBuilder md = new StringBuilder();
        String doc = d.getMember("doc").isNull() ? "" : d.getMember("doc").asString();
        // Before the attribute; else before a definition of that name in the function's file
        // (lib.mkForce is `mkForce = mkOverride 50;` there, inherited into lib); else before the
        // function itself.
        Value attr = d.getMember("attrPos"), lambda = d.getMember("lambdaPos");
        String name = d.getMember("name").asString();
        if (doc.isEmpty() && !attr.isNull()) doc = DocComments.before(attr.getMember("file").asString(), (int) attr.getMember("line").asLong());
        if (doc.isEmpty() && !lambda.isNull()) doc = DocComments.named(lambda.getMember("file").asString(), name);
        if (doc.isEmpty() && !lambda.isNull()) doc = DocComments.before(lambda.getMember("file").asString(), (int) lambda.getMember("line").asLong());
        md.append(doc);
        if (!d.getMember("default").isNull()) md.append(md.isEmpty() ? "" : "\n\n").append("Default: `").append(d.getMember("default").asString()).append('`');
        return md.toString();
    }

    private void log(String message) {
        notify("window/logMessage", obj("type", 4L, "message", Bytes.fromJava(message)));
    }

    /** A builtin's completion item, with its signature and documentation. */
    private static Map<String, Object> builtinItem(String name, long kind) {
        String signature = BuiltinDocs.signature(name);
        Map<String, Object> it = item(name, kind, signature != null ? signature : "built in");
        if (BuiltinDocs.doc(name) != null) it.put("documentation", obj("kind", "markdown", "value", Bytes.fromJava(BuiltinDocs.doc(name))));
        return it;
    }

    private static Map<String, Object> item(String label, long kind, String detail) {
        return obj("label", Bytes.fromJava(label), "kind", kind, "detail", Bytes.fromJava(detail));
    }

    // ------------------------------------------------------------ positions

    /** Line starts, for LSP positions (lines, and UTF-16 code units in them, as Java's strings). */
    static final class Lines {
        private final int[] starts;
        private final int length;

        Lines(String text) {
            List<Integer> s = new ArrayList<>();
            s.add(0);
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') s.add(i + 1);
            starts = s.stream().mapToInt(Integer::intValue).toArray();
            length = text.length();
        }

        int line(int offset) {
            int lo = 0, hi = starts.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (starts[mid] <= offset) lo = mid;
                else hi = mid - 1;
            }
            return lo;
        }

        Map<String, Object> position(int offset) {
            int l = line(offset);
            return obj("line", (long) l, "character", (long) (offset - starts[l]));
        }

        int offset(int line, int character) {
            if (line >= starts.length) return length;
            return Math.min(starts[line] + character, line + 1 < starts.length ? starts[line + 1] : length);
        }
    }
}
