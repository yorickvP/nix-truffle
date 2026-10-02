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
    private final Context context;
    private final Set<String> globals = new HashSet<>();
    private final List<String> builtinNames = new ArrayList<>();
    private final Map<String, Doc> docs = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean shutdown;
    /** The workspace's directory, and the client's initializationOptions. */
    private volatile String rootPath;
    private volatile Map<String, Object> initOptions = Map.of();

    /** The thread that evaluates (all of the fields below are its), and the timeouts' timer. */
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> daemon(r, "lsp-eval"));
    private final java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "lsp-timer"));
    private final Map<Object, Task> tasks = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Task running;
    private Value lspEval;
    /** {@code name: request: ...}: the resolver for the NixOS configuration {@code name} (or the first). */
    private Value resolvers;
    private final Map<String, Value> resolverFor = new HashMap<>();
    private List<String> configNames;
    private Value describe;

    /** An open document as it is at one time, and its parse. */
    private static final class Doc {
        final String uri;
        final String text;
        final Lines lines;
        final Expr root;
        final Parser.SyntaxError error;
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
            Scopes scopes = null;
            try {
                root = parse(uri, text);
                scopes = Scopes.analyze(text, root, globals);
            } catch (Parser.SyntaxError e) {
                error = e;
            }
            this.root = root;
            this.error = error;
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

    LspServer(InputStream in, OutputStream out, Context context) {
        this.in = new BufferedInputStream(in);
        this.out = out;
        this.context = context;
        Value names = context.eval("nix", "__nixTruffle.globals null");
        for (long i = 0; i < names.getArraySize(); i++) globals.add(names.getArrayElement(i).asString());
        Value builtins = context.eval("nix", "builtins.attrNames builtins");
        for (long i = 0; i < builtins.getArraySize(); i++) builtinNames.add(builtins.getArrayElement(i).asString());
    }

    public static int run(Context context) throws IOException {
        return new LspServer(System.in, System.out, context).serve();
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
        long seconds = initOptions.get("evalTimeout") instanceof Number n ? n.longValue() : 10;
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
                yield obj("capabilities", obj(
                        "textDocumentSync", 1L,
                        "definitionProvider", true,
                        "referencesProvider", true,
                        "hoverProvider", true,
                        "completionProvider", obj("triggerCharacters", List.of("."), "resolveProvider", true)),
                        "serverInfo", obj("name", "nix-truffle"));
            }
            case "initialized", "$/setTrace", "workspace/didChangeConfiguration", "textDocument/didSave" -> null;
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
            int at = Math.min(doc.error.offset, doc.text.length());
            out.add(diagnostic(doc, at, Math.min(at + 1, doc.text.length()), 1, Bytes.toJava(doc.error.detail), null));
            return out;
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
        Scopes.Use u = useAt(doc, offset);
        if (u != null && u.def() != null) return location(doc.uri, doc, u.def().pos(), u.def().name().length());
        Scopes.Def d = defAt(doc, offset);
        if (d != null) return location(doc.uri, doc, d.pos(), d.name().length());
        if (doc.scopes != null) {
            for (Scopes.PathRef p : doc.scopes.paths) {
                if (p.pos() <= offset && offset <= p.pos() + p.text().length()) {
                    Path file = pathFile(doc.uri, p.text());
                    if (file != null) return obj("uri", Bytes.fromJava(file.toUri().toString()), "range", obj("start", obj("line", 0L, "character", 0L), "end", obj("line", 0L, "character", 0L)));
                }
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

    private Object references(Doc doc, int offset) {
        Scopes.Use u = useAt(doc, offset);
        Scopes.Def def = u != null ? u.def() : defAt(doc, offset);
        if (def == null) return null;
        List<Object> out = new ArrayList<>();
        out.add(location(doc.uri, doc, def.pos(), def.name().length()));
        for (Scopes.Use x : doc.scopes.uses) if (x.def() == def) out.add(location(doc.uri, doc, x.pos(), x.name().length()));
        return out;
    }

    private Object hover(Doc doc, int offset) {
        Object option = hoverOption(doc, offset);
        if (option != null) return option;
        Object attr = hoverAttribute(doc, offset);
        if (attr != null) return attr;
        Scopes.Use u = useAt(doc, offset);
        if (u == null) return null;
        String text = switch (u.kind()) {
            case LOCAL -> "`" + u.name() + "`: " + (u.def().kind().equals("argument") ? "function argument" : u.def().kind().equals("rec") ? "attribute of a recursive set" : "let binding")
                    + ", line " + (doc.lines.line(u.def().pos()) + 1);
            case GLOBAL -> "`" + u.name() + "`: built in" + (builtinNames.contains(u.name()) ? " (`builtins." + u.name() + "`)" : "");
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
        Value p = evaluate(doc, start, start, parent);
        if (p == null) return null;
        try {
            String name = text.substring(start, end);
            Value d = describe().execute(p, name);
            String md = "`" + parent + "." + name + "`: " + d.getMember("kind").asString()
                    + (d.getMember("detail").isNull() ? "" : " `" + d.getMember("detail").asString() + "`");
            String docs = documentation(d);
            if (!docs.isEmpty()) md += "\n\n" + docs;
            return obj("contents", obj("kind", "markdown", "value", Bytes.fromJava(md)), "range", range(doc, start, end));
        } catch (org.graalvm.polyglot.PolyglotException e) {
            log("hover " + parent + ": " + e.getMessage());
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
        List<String> optionPath = optionPath(doc, start, offset);
        if (optionPath != null) return optionItems(doc, optionPath, prefix);
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
            if (g.startsWith(prefix) && (!g.startsWith("__") || prefix.startsWith("_")) && seen.add(g)) items.add(item(g, g.equals("builtins") ? 9 : 3, "built in"));
        }
        for (String k : KEYWORDS) if (k.startsWith(prefix)) items.add(item(k, 14, "keyword"));
        return items;
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
        int b = ps;
        while (b > 0 && Character.isWhitespace(text.charAt(b - 1))) b--;
        if (b == 0 || text.charAt(b - 1) != '{' && text.charAt(b - 1) != ';') return null;
        List<String> typed = components(text.substring(ps, wordStart));
        if (typed == null) return null;
        Parseable p = parseable(doc, wordStart, offset);
        if (p == null) return null;
        List<String> around = enclosing(p.root, ps);
        if (around == null) return null;
        List<String> path = new ArrayList<>(around);
        path.addAll(typed);
        return path;
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
                    resolver: path:
                    let
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
            List<Object> p = new ArrayList<>(path);
            Value v = optionsHelper.execute(resolver(doc), context.eval("nix", "x: x").execute(org.graalvm.polyglot.proxy.ProxyArray.fromList(p)));
            return v.isNull() ? null : v;
        } catch (org.graalvm.polyglot.PolyglotException e) {
            log("options at " + path + ": " + e.getMessage());
            return null;
        }
    }

    private Object optionItems(Doc doc, List<String> path, String prefix) {
        List<Object> items = new ArrayList<>();
        Value opts = optionsAt(doc, path);
        if (opts == null || !opts.hasMembers()) return items;
        for (String n : opts.getMemberKeys()) {
            if (!n.startsWith(prefix) || n.startsWith("_")) continue;
            Value o = opts.getMember(n);
            boolean option = o.hasMember("_type") && "option".equals(o.getMember("_type").isString() ? o.getMember("_type").asString() : null);
            Map<String, Object> it = obj("label", Bytes.fromJava(n), "kind", option ? 10L : 9L,
                    "data", obj("uri", Bytes.fromJava(doc.uri), "option", path.stream().map(x -> (Object) Bytes.fromJava(x)).toList(), "name", Bytes.fromJava(n)));
            items.add(it);
        }
        return items;
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
            return obj("contents", obj("kind", "markdown", "value", Bytes.fromJava(md)), "range", range(doc, start, end));
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
     * What a file's functions get for their arguments: given {@code { names, optional }} (formals)
     * or {@code { arg }}, the arguments (lazily). With the NixOS configuration whose name is a
     * directory of the file's path ({@code nixos/machines/frumar/...}), else the flake's first.
     */
    private Value resolver(Doc doc) {
        String name = null;
        try {
            if (configNames == null) {
                Value names = resolvers().execute(Value.asValue(null)).execute(context.eval("nix", "{ arg = \"__configNames\"; }"));
                configNames = new ArrayList<>();
                for (long i = 0; i < names.getArraySize(); i++) configNames.add(names.getArrayElement(i).asString());
            }
            for (Path p = Path.of(URI.create(doc.uri)).getParent(); p != null && name == null; p = p.getParent()) {
                if (p.getFileName() != null && configNames.contains(p.getFileName().toString())) name = p.getFileName().toString();
            }
        } catch (org.graalvm.polyglot.PolyglotException | IllegalArgumentException e) {
            log("NixOS configurations: " + e.getMessage());
            configNames = List.of();
        }
        String key = String.valueOf(name);
        Value r = resolverFor.get(key);
        if (r == null) {
            r = resolvers().execute(name);
            resolverFor.put(key, r);
        }
        return r;
    }

    private Value resolvers() {
        if (resolvers == null) {
            String nixpkgs = initOptions.get("nixpkgs") instanceof String e ? Bytes.toJava(e) : "import <nixpkgs> { }";
            String nixos = initOptions.get("nixos") instanceof String e ? Bytes.toJava(e)
                    : "if configs == { } then null else configs.${if configName != null && configs ? ${configName} then configName else builtins.head (builtins.attrNames configs)}";
            String flake = rootPath != null && Files.exists(Path.of(rootPath, "flake.nix")) ? "builtins.getFlake " + nixString("path:" + rootPath) : "null";
            resolvers = context.eval("nix", """
                    configName:
                    let
                      flake = %s;
                      configs = if flake != null then flake.nixosConfigurations or { } else { };
                      nixos = %s;
                      pkgs = if nixos != null then nixos.pkgs else %s;
                      special = { inherit pkgs; lib = pkgs.lib; }
                        // (if nixos != null then { inherit (nixos) config options; } else { })
                        // (if flake != null then flake.inputs // { self = flake; inherit (flake) inputs; } else { });
                      value = name: special.${name} or pkgs.${name} or (throw "nix-truffle lsp: no value for '${name}'");
                    in
                    request:
                      if request ? arg && request.arg == "__configNames" then builtins.attrNames configs
                      else if request ? arg then value request.arg
                      else builtins.listToAttrs (map (name: { inherit name; value = value name; })
                        (builtins.filter (n: special ? ${n} || pkgs ? ${n} || !builtins.elem n request.optional) request.names))
                    """.formatted(flake, nixos, nixpkgs));
        }
        return resolvers;
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
                    in {
                      kind = if option then "option" else if drv then "package" else if t == "lambda" then "function" else t;
                      detail = safe (if option then v.type.description or "option"
                        else if drv then v.name or "derivation"
                        else if t == "set" then "attribute set"
                        else if t == "lambda" then
                          let args = builtins.functionArgs v; in
                          if args == { } then "function"
                          else "{ " + builtins.concatStringsSep ", " (map (n: if args.${n} then n + " ? ..." else n) (builtins.attrNames args)) + " }: ..."
                        else if builtins.elem t [ "int" "float" "bool" "null" ] then builtins.toJSON v
                        else if t == "string" then builtins.toJSON (builtins.substring 0 80 v)
                        else t);
                      doc = safe (if option then text (v.description or "")
                        else if drv then v.meta.description or ""
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
        if (data.get("option") instanceof List<?> l) {
            parent = optionsAt(doc, l.stream().map(x -> Bytes.toJava((String) x)).toList());
        } else {
            int offset = (int) Math.min(((Number) data.get("offset")).longValue(), doc.text.length());
            parent = evaluate(doc, offset, offset, Bytes.toJava(Json.str(data.get("expression"))));
        }
        if (parent == null) return item;
        Map<String, Object> out = new LinkedHashMap<>(item);
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
