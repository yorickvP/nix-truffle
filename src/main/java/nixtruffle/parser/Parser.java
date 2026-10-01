package nixtruffle.parser;

import com.oracle.truffle.api.source.Source;
import nixtruffle.parser.Expr.*;
import nixtruffle.parser.Expr.Binding.Assign;
import nixtruffle.parser.Expr.Binding.Inherit;
import nixtruffle.runtime.NixException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hand-written lexer + recursive descent parser for the Nix expression language.
 *
 * <p>The lexer is a pure function of the position ({@link #lexAt}), so arbitrary lookahead is just
 * lexing ahead, and string literals are scanned character by character straight from the source,
 * recursing into {@link #parseExpr} for {@code ${...}} interpolations.
 */
public final class Parser {
    enum T {
        INT, FLOAT, ID, PATH, HPATH, SPATH, STR_OPEN, IND_OPEN,
        ELLIPSIS, DOT, EQ, NEQ, LEQ, GEQ, LT, GT, AND, OR, IMPL, UPDATE, CONCAT,
        PLUS, MINUS, STAR, SLASH, NOT, QUESTION, AT, COLON, SEMI, COMMA, ASSIGN,
        LPAREN, RPAREN, LBRACK, RBRACK, LBRACE, RBRACE, DOLLAR_CURLY, PIPE_FROM, PIPE_INTO, EOF,
        IF, THEN, ELSE, ASSERT, WITH, LET, IN, REC, INHERIT, OR_KW
    }

    record Tok(T type, String text, int start, int end) {}

    private static final Map<String, T> KEYWORDS = Map.of(
            "if", T.IF, "then", T.THEN, "else", T.ELSE, "assert", T.ASSERT, "with", T.WITH,
            "let", T.LET, "in", T.IN, "rec", T.REC, "inherit", T.INHERIT, "or", T.OR_KW);

    private final Source source;
    private final String src;
    private final int n;
    private Tok cur;

    public Parser(Source source) {
        this.source = source;
        this.src = source.getCharacters().toString();
        this.n = src.length();
    }

    public Expr parseFile() {
        cur = lexAt(0);
        Expr e = parseExpr();
        if (cur.type != T.EOF) throw error("unexpected " + describe(cur) + ", expecting end of file", cur.start);
        return e;
    }

    // ------------------------------------------------------------------ lexer

    private static boolean isIdStart(char c) { return Character.isLetter(c) && c < 128 || c == '_'; }
    private static boolean isIdChar(char c) { return isIdStart(c) || c >= '0' && c <= '9' || c == '\'' || c == '-'; }
    private static boolean isPathChar(char c) {
        return c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == '+');
    }
    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    private char at(int i) { return i < n ? src.charAt(i) : '\0'; }

    private int skipTrivia(int p) {
        while (p < n) {
            char c = src.charAt(p);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                p++;
            } else if (c == '#') {
                while (p < n && src.charAt(p) != '\n') p++;
            } else if (c == '/' && at(p + 1) == '*') {
                int end = src.indexOf("*/", p + 2);
                if (end < 0) throw error("unterminated comment", p);
                p = end + 2;
            } else {
                break;
            }
        }
        return p;
    }

    /** Matches {@code [a-zA-Z0-9._\-+]*(/[a-zA-Z0-9._\-+]+)+}; returns the end offset or -1. */
    private int matchPath(int p) {
        int q = p;
        while (q < n && isPathChar(src.charAt(q))) q++;
        int segments = 0;
        while (at(q) == '/' && isPathChar(at(q + 1))) {
            q++;
            while (q < n && isPathChar(src.charAt(q))) q++;
            segments++;
        }
        // PATH_SEG followed by an interpolation: `./${x}`, `./dir/${x}`.
        if (at(q) == '/' && at(q + 1) == '$' && at(q + 2) == '{') return q + 1;
        if (segments == 0) return -1;
        if (at(q) == '/') throw error("path has a trailing slash", p);
        return q;
    }

    Tok lexAt(int start) {
        int p = skipTrivia(start);
        if (p >= n) return new Tok(T.EOF, "", p, p);
        char c = src.charAt(p);

        if (c == '~' && at(p + 1) == '/') {
            int q = matchPath(p + 1);
            if (q > 0) return new Tok(T.HPATH, src.substring(p, q), p, q);
        }
        if (isPathChar(c) || c == '/') {
            int q = matchPath(p);
            if (q > 0) return new Tok(T.PATH, src.substring(p, q), p, q);
        }
        if (c == '<' && isPathChar(at(p + 1))) {
            int q = p + 1;
            while (q < n && (isPathChar(src.charAt(q)) || src.charAt(q) == '/')) q++;
            if (at(q) == '>') return new Tok(T.SPATH, src.substring(p + 1, q), p, q + 1);
        }
        if (isDigit(c) || c == '.' && isDigit(at(p + 1))) return lexNumber(p);
        if (isIdStart(c)) {
            int q = p + 1;
            while (q < n && isIdChar(src.charAt(q))) q++;
            String id = name(src.substring(p, q));
            return new Tok(KEYWORDS.getOrDefault(id, T.ID), id, p, q);
        }
        if (c == '"') return new Tok(T.STR_OPEN, "\"", p, p + 1);
        if (c == '\'' && at(p + 1) == '\'') return new Tok(T.IND_OPEN, "''", p, p + 2);

        String three = src.startsWith("...", p) ? "..." : null;
        if (three != null) return new Tok(T.ELLIPSIS, three, p, p + 3);
        String two = p + 2 <= n ? src.substring(p, p + 2) : "";
        T t2 = switch (two) {
            case "${" -> T.DOLLAR_CURLY;
            case "==" -> T.EQ;
            case "!=" -> T.NEQ;
            case "<=" -> T.LEQ;
            case ">=" -> T.GEQ;
            case "&&" -> T.AND;
            case "||" -> T.OR;
            case "->" -> T.IMPL;
            case "//" -> T.UPDATE;
            case "++" -> T.CONCAT;
            case "|>" -> T.PIPE_FROM;
            case "<|" -> T.PIPE_INTO;
            default -> null;
        };
        if (t2 != null) return new Tok(t2, two, p, p + 2);
        T t1 = switch (c) {
            case '.' -> T.DOT;
            case '<' -> T.LT;
            case '>' -> T.GT;
            case '+' -> T.PLUS;
            case '-' -> T.MINUS;
            case '*' -> T.STAR;
            case '/' -> T.SLASH;
            case '!' -> T.NOT;
            case '?' -> T.QUESTION;
            case '@' -> T.AT;
            case ':' -> T.COLON;
            case ';' -> T.SEMI;
            case ',' -> T.COMMA;
            case '=' -> T.ASSIGN;
            case '(' -> T.LPAREN;
            case ')' -> T.RPAREN;
            case '[' -> T.LBRACK;
            case ']' -> T.RBRACK;
            case '{' -> T.LBRACE;
            case '}' -> T.RBRACE;
            default -> throw error("unexpected character '" + c + "'", p);
        };
        return new Tok(t1, String.valueOf(c), p, p + 1);
    }

    /** INT {@code [0-9]+}; FLOAT {@code (([1-9][0-9]*\.[0-9]*)|(0?\.[0-9]+))([Ee][+-]?[0-9]+)?}. */
    private Tok lexNumber(int p) {
        int q = p;
        while (isDigit(at(q))) q++;
        String digits = src.substring(p, q);
        boolean isFloat = false;
        if (at(q) == '.') {
            if (!digits.isEmpty() && digits.charAt(0) != '0') {
                isFloat = true;
                q++;
                while (isDigit(at(q))) q++;
            } else if ((digits.isEmpty() || digits.equals("0")) && isDigit(at(q + 1))) {
                isFloat = true;
                q++;
                while (isDigit(at(q))) q++;
            }
        }
        if (isFloat && (at(q) == 'e' || at(q) == 'E')) {
            int r = q + 1;
            if (at(r) == '+' || at(r) == '-') r++;
            if (isDigit(at(r))) {
                while (isDigit(at(r))) r++;
                q = r;
            }
        }
        return new Tok(isFloat ? T.FLOAT : T.INT, src.substring(p, q), p, q);
    }

    // ----------------------------------------------------------------- parser

    private void advance() { cur = lexAt(cur.end); }

    private Tok expect(T type) {
        if (cur.type != type) throw error("unexpected " + describe(cur) + ", expecting " + type, cur.start);
        Tok t = cur;
        advance();
        return t;
    }

    private static String describe(Tok t) {
        return t.type == T.EOF ? "end of file" : "'" + t.text + "'";
    }

    public Expr parseExpr() {
        int pos = cur.start;
        switch (cur.type) {
            case ID -> {
                Tok next = lexAt(cur.end);
                if (next.type == T.COLON) {
                    String name = cur.text;
                    advance();
                    advance();
                    return new Lambda(name, null, parseExpr(), pos);
                }
                if (next.type == T.AT) {
                    String name = cur.text;
                    advance();
                    advance();
                    expect(T.LBRACE);
                    Formals formals = parseFormals();
                    expect(T.COLON);
                    return new Lambda(name, formals, parseExpr(), pos);
                }
            }
            case LBRACE -> {
                if (isFormalsStart()) {
                    advance();
                    Formals formals = parseFormals();
                    String name = null;
                    if (cur.type == T.AT) {
                        advance();
                        name = expect(T.ID).text;
                    }
                    expect(T.COLON);
                    return new Lambda(name, formals, parseExpr(), pos);
                }
            }
            case LET -> {
                if (lexAt(cur.end).type == T.LBRACE) throw error("legacy 'let { ... }' syntax is not supported", pos);
                advance();
                List<Binding> binds = parseBindings(T.IN);
                expect(T.IN);
                return new Let(binds, parseExpr(), pos);
            }
            case WITH -> {
                advance();
                Expr env = parseExpr();
                expect(T.SEMI);
                return new With(env, parseExpr(), pos);
            }
            case ASSERT -> {
                advance();
                Expr cond = parseExpr();
                expect(T.SEMI);
                return new Assert(cond, parseExpr(), pos);
            }
            default -> {}
        }
        return parseIf();
    }

    private boolean isFormalsStart() {
        Tok t1 = lexAt(cur.end);
        return switch (t1.type) {
            case RBRACE -> {
                T t2 = lexAt(t1.end).type;
                yield t2 == T.COLON || t2 == T.AT;
            }
            case ELLIPSIS -> true;
            case ID -> {
                T t2 = lexAt(t1.end).type;
                yield t2 == T.COMMA || t2 == T.QUESTION || t2 == T.RBRACE;
            }
            default -> false;
        };
    }

    /** Parses formals after the opening brace, consuming the closing brace. */
    private Formals parseFormals() {
        List<Formal> formals = new ArrayList<>();
        boolean ellipsis = false;
        while (cur.type != T.RBRACE) {
            if (cur.type == T.ELLIPSIS) {
                advance();
                ellipsis = true;
            } else {
                Tok id = expect(T.ID);
                Expr fallback = null;
                if (cur.type == T.QUESTION) {
                    advance();
                    fallback = parseExpr();
                }
                for (Formal f : formals) {
                    if (f.name().equals(id.text)) throw error("duplicate formal function argument '" + id.text + "'", id.start);
                }
                formals.add(new Formal(id.text, fallback, id.start));
            }
            if (cur.type != T.COMMA) break;
            advance();
        }
        expect(T.RBRACE);
        return new Formals(formals, ellipsis);
    }

    private Expr parseIf() {
        if (cur.type == T.IF) {
            int pos = cur.start;
            advance();
            Expr cond = parseExpr();
            expect(T.THEN);
            Expr then = parseExpr();
            expect(T.ELSE);
            return new If(cond, then, parseExpr(), pos);
        }
        return parseOp(0);
    }

    // Binding powers follow the %left/%right/%nonassoc table in Nix's parser.y.
    private static int precedence(T t) {
        return switch (t) {
            case PIPE_FROM, PIPE_INTO -> 0;
            case IMPL -> 1;
            case OR -> 2;
            case AND -> 3;
            case EQ, NEQ -> 4;
            case LT, GT, LEQ, GEQ -> 5;
            case UPDATE -> 6;
            // 7 is prefix '!'
            case PLUS, MINUS -> 8;
            case STAR, SLASH -> 9;
            case CONCAT -> 10;
            case QUESTION -> 11;
            default -> -1;
        };
    }

    private static boolean rightAssoc(T t) {
        return t == T.IMPL || t == T.UPDATE || t == T.CONCAT || t == T.PIPE_INTO;
    }

    private Expr parseOp(int minPrec) {
        Expr lhs = parseUnary();
        while (true) {
            int prec = precedence(cur.type);
            if (prec < 0 || prec < minPrec) return lhs;
            Tok op = cur;
            advance();
            if (op.type == T.QUESTION) {
                lhs = new HasAttr(lhs, parseAttrPath(), op.start);
                continue;
            }
            Expr rhs = parseOp(rightAssoc(op.type) ? prec : prec + 1);
            lhs = switch (op.type) {
                case PIPE_FROM -> new App(rhs, List.of(lhs), op.start);
                case PIPE_INTO -> new App(lhs, List.of(rhs), op.start);
                default -> new BinOp(op.text, lhs, rhs, op.start);
            };
        }
    }

    private Expr parseUnary() {
        int pos = cur.start;
        if (cur.type == T.NOT) {
            advance();
            return new Not(parseOp(8), pos);
        }
        if (cur.type == T.MINUS) {
            advance();
            return new Neg(parseUnary(), pos);
        }
        return parseApp();
    }

    private static boolean startsSimple(T t) {
        return switch (t) {
            case ID, OR_KW, INT, FLOAT, STR_OPEN, IND_OPEN, PATH, HPATH, SPATH, LPAREN, REC, LBRACE, LBRACK -> true;
            default -> false;
        };
    }

    private Expr parseApp() {
        int pos = cur.start;
        Expr fn = parseSelect();
        if (!startsSimple(cur.type)) return fn;
        List<Expr> args = new ArrayList<>();
        while (startsSimple(cur.type)) args.add(parseSelect());
        return new App(fn, args, pos);
    }

    private Expr parseSelect() {
        Expr e = parseSimple();
        if (cur.type != T.DOT) return e;
        int pos = cur.start;
        advance();
        List<AttrKey> path = parseAttrPath();
        Expr fallback = null;
        if (cur.type == T.OR_KW) {
            advance();
            fallback = parseSelect();
        }
        return new Select(e, path, fallback, pos);
    }

    private Expr parseSimple() {
        Tok t = cur;
        switch (t.type) {
            case ID, OR_KW -> {
                advance();
                return t.text.equals("__curPos") ? new CurPos(t.start) : new Var(t.text, t.start);
            }
            case INT -> {
                advance();
                try {
                    return new Int(Long.parseLong(t.text), t.start);
                } catch (NumberFormatException e) {
                    throw error("invalid integer '" + t.text + "'", t.start);
                }
            }
            case FLOAT -> {
                advance();
                double d = Double.parseDouble(t.text);
                // Like strtod's ERANGE: overflow, and underflow to a subnormal or zero.
                boolean underflow = d == 0 ? t.text.replaceAll("[eE].*", "").matches(".*[1-9].*") : Math.abs(d) < Double.MIN_NORMAL;
                if (Double.isInfinite(d) || underflow) throw error("invalid float '" + t.text + "'", t.start);
                return new Flt(d, t.start);
            }
            case STR_OPEN -> { return parseString(); }
            case IND_OPEN -> { return parseIndString(); }
            case PATH, HPATH -> {
                if (at(t.end) == '$' && at(t.end + 1) == '{') return parseInterpolatedPath(t);
                advance();
                return new PathLit(t.text, t.start);
            }
            case SPATH -> {
                advance();
                return new SearchPath(t.text, t.start);
            }
            case LPAREN -> {
                advance();
                Expr e = parseExpr();
                expect(T.RPAREN);
                return e;
            }
            case REC -> {
                advance();
                expect(T.LBRACE);
                List<Binding> binds = parseBindings(T.RBRACE);
                expect(T.RBRACE);
                return new Attrs(true, binds, t.start);
            }
            case LBRACE -> {
                advance();
                List<Binding> binds = parseBindings(T.RBRACE);
                expect(T.RBRACE);
                return new Attrs(false, binds, t.start);
            }
            case LBRACK -> {
                advance();
                List<Expr> items = new ArrayList<>();
                while (cur.type != T.RBRACK) {
                    if (!startsSimple(cur.type)) throw error("unexpected " + describe(cur) + " in list", cur.start);
                    items.add(parseSelect());
                }
                advance();
                return new ListE(items, t.start);
            }
            default -> throw error("unexpected " + describe(t), t.start);
        }
    }

    private List<Binding> parseBindings(T terminator) {
        List<Binding> binds = new ArrayList<>();
        while (cur.type != terminator) {
            int pos = cur.start;
            if (cur.type == T.INHERIT) {
                advance();
                Expr from = null;
                if (cur.type == T.LPAREN) {
                    advance();
                    from = parseExpr();
                    expect(T.RPAREN);
                }
                List<String> names = new ArrayList<>();
                List<Integer> namePos = new ArrayList<>();
                while (cur.type != T.SEMI) {
                    namePos.add(cur.start);
                    AttrKey key = parseAttr();
                    if (key.name() == null) throw error("dynamic attributes not allowed in inherit", pos);
                    names.add(key.name());
                }
                advance();
                binds.add(new Inherit(from, names, namePos, pos));
            } else {
                List<AttrKey> path = parseAttrPath();
                expect(T.ASSIGN);
                Expr value = parseExpr();
                expect(T.SEMI);
                binds.add(new Assign(path, value, pos));
            }
        }
        return binds;
    }

    private List<AttrKey> parseAttrPath() {
        List<AttrKey> path = new ArrayList<>();
        path.add(parseAttr());
        while (cur.type == T.DOT) {
            advance();
            path.add(parseAttr());
        }
        return path;
    }

    /**
     * Identifiers and attribute names, one string each for all files: function bodies are
     * translated when they first run, so their syntax trees stay, and a NixOS evaluation's would
     * otherwise hold a million and a half copies of a few thousand names.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> NAMES = new java.util.concurrent.ConcurrentHashMap<>();

    static String name(String s) {
        String n = NAMES.putIfAbsent(s, s);
        return n != null ? n : s;
    }

    private AttrKey parseAttr() {
        Tok t = cur;
        switch (t.type) {
            case ID, OR_KW -> {
                advance();
                return AttrKey.of(t.text);
            }
            case STR_OPEN -> {
                Str s = parseString();
                return s.isLiteral() ? AttrKey.of(name(s.literal())) : new AttrKey(null, s);
            }
            case DOLLAR_CURLY -> {
                advance();
                Expr e = parseExpr();
                expect(T.RBRACE);
                return new AttrKey(null, e);
            }
            default -> throw error("unexpected " + describe(t) + ", expecting attribute name", t.start);
        }
    }

    /** Parses {@code ${ expr }} starting at the {@code $}; returns the expression and leaves {@code cur} at '}'. */
    private Expr parseInterpolation(int p) {
        cur = lexAt(p + 2);
        Expr e = parseExpr();
        if (cur.type != T.RBRACE) throw error("unexpected " + describe(cur) + ", expecting '}'", cur.start);
        return e;
    }

    /** The INPATH lexer states: path characters and {@code ${...}} until anything else. */
    private Expr parseInterpolatedPath(Tok t) {
        List<Object> rest = new ArrayList<>();
        int p = t.end;
        String last = t.text;
        while (true) {
            if (at(p) == '$' && at(p + 1) == '{') {
                rest.add(parseInterpolation(p));
                p = cur.end;
                last = "";
                continue;
            }
            int q = p;
            while (q < n && (isPathChar(src.charAt(q)) || src.charAt(q) == '/')) q++;
            if (q == p) break;
            last = src.substring(p, q);
            rest.add(last);
            p = q;
        }
        if (last.endsWith("/")) throw error("path has a trailing slash", t.start);
        cur = lexAt(p);
        return new PathInterp(t.text, rest, t.start);
    }

    private Str parseString() {
        int start = cur.start;
        int p = cur.end;
        List<Object> parts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (p >= n) throw error("unterminated string", start);
            char c = src.charAt(p);
            if (c == '"') {
                p++;
                break;
            } else if (c == '\\') {
                if (p + 1 >= n) throw error("unterminated string", start);
                sb.append(unescape(src.charAt(p + 1)));
                p += 2;
            } else if (c == '$' && at(p + 1) == '{') {
                if (!sb.isEmpty()) parts.add(sb.toString());
                sb.setLength(0);
                parts.add(parseInterpolation(p));
                p = cur.end;
            } else if (c == '$' && at(p + 1) == '$') {
                sb.append("$$");
                p += 2;
            } else {
                sb.append(c);
                p++;
            }
        }
        if (!sb.isEmpty() || parts.isEmpty()) parts.add(sb.toString());
        cur = lexAt(p);
        return new Str(parts, start);
    }

    private static char unescape(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> c;
        };
    }

    /** A piece of an indented string: text (possibly subject to indentation stripping) or an interpolation. */
    private record IndPart(String text, boolean indented, Expr expr) {}

    private Str parseIndString() {
        int start = cur.start;
        int p = cur.end;
        // The opening '' swallows trailing spaces and one newline (lexer rule \'\'(\ *\n)?).
        int q = p;
        while (at(q) == ' ') q++;
        if (at(q) == '\n') p = q + 1;

        List<IndPart> parts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (p >= n) throw error("unterminated indented string", start);
            if (src.startsWith("''", p)) {
                String escaped = null;
                if (at(p + 2) == '\'') {
                    escaped = "''";
                    p += 3;
                } else if (at(p + 2) == '$') {
                    escaped = "$";
                    p += 3;
                } else if (at(p + 2) == '\\' && p + 3 < n) {
                    escaped = String.valueOf(unescape(src.charAt(p + 3)));
                    p += 4;
                }
                if (!sb.isEmpty()) parts.add(new IndPart(sb.toString(), true, null));
                sb.setLength(0);
                if (escaped == null) {
                    p += 2;
                    break;
                }
                parts.add(new IndPart(escaped, false, null));
            } else if (src.startsWith("${", p)) {
                if (!sb.isEmpty()) parts.add(new IndPart(sb.toString(), true, null));
                sb.setLength(0);
                parts.add(new IndPart(null, false, parseInterpolation(p)));
                p = cur.end;
            } else if (src.startsWith("$$", p)) {
                sb.append("$$");
                p += 2;
            } else {
                sb.append(src.charAt(p));
                p++;
            }
        }
        if (!sb.isEmpty()) parts.add(new IndPart(sb.toString(), true, null));
        cur = lexAt(p);
        return new Str(stripIndentation(parts), start);
    }

    /** Port of {@code stripIndentation} from Nix's parser. */
    private static List<Object> stripIndentation(List<IndPart> parts) {
        boolean atStartOfLine = true;
        int minIndent = 1000000;
        int curIndent = 0;
        for (IndPart part : parts) {
            if (part.expr != null || !part.indented) {
                if (atStartOfLine) {
                    atStartOfLine = false;
                    minIndent = Math.min(minIndent, curIndent);
                }
                continue;
            }
            for (char c : part.text.toCharArray()) {
                if (atStartOfLine) {
                    if (c == ' ') {
                        curIndent++;
                    } else if (c == '\n') {
                        curIndent = 0;
                    } else {
                        atStartOfLine = false;
                        minIndent = Math.min(minIndent, curIndent);
                    }
                } else if (c == '\n') {
                    atStartOfLine = true;
                    curIndent = 0;
                }
            }
        }

        List<Object> out = new ArrayList<>();
        atStartOfLine = true;
        int curDropped = 0;
        for (int i = 0; i < parts.size(); i++) {
            IndPart part = parts.get(i);
            if (part.expr != null) {
                atStartOfLine = false;
                curDropped = 0;
                out.add(part.expr);
                continue;
            }
            StringBuilder s2 = new StringBuilder();
            for (char c : part.text.toCharArray()) {
                if (atStartOfLine) {
                    if (c == ' ') {
                        if (curDropped++ >= minIndent) s2.append(c);
                    } else if (c == '\n') {
                        curDropped = 0;
                        s2.append(c);
                    } else {
                        atStartOfLine = false;
                        curDropped = 0;
                        s2.append(c);
                    }
                } else {
                    s2.append(c);
                    if (c == '\n') atStartOfLine = true;
                }
            }
            String s = s2.toString();
            if (i == parts.size() - 1) {
                // Remove the last line if it is empty and consists only of spaces.
                int nl = s.lastIndexOf('\n');
                if (nl >= 0 && s.substring(nl + 1).chars().allMatch(ch -> ch == ' ')) s = s.substring(0, nl + 1);
            }
            if (!out.isEmpty() && out.get(out.size() - 1) instanceof String prev) {
                out.set(out.size() - 1, prev + s);
            } else {
                out.add(s);
            }
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    private NixException error(String message, int pos) {
        return new NixException("syntax error, " + message + "\n       at " + location(source, pos), null);
    }

    public static String location(Source source, int pos) {
        int p = Math.max(0, Math.min(pos, source.getLength()));
        if (source.getLength() == 0) return source.getName() + ":1:1";
        if (p == source.getLength()) p--;
        int line = source.getLineNumber(p);
        int col = source.getColumnNumber(p);
        return source.getName() + ":" + line + ":" + col;
    }
}
