package nixtruffle.builtins;

import nixtruffle.runtime.NixException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regular expressions as {@code builtins.match} and {@code builtins.split} see them.
 *
 * <p>CppNix hands patterns to libstdc++'s {@code std::regex} with the POSIX {@code extended}
 * grammar, so that implementation, not POSIX, defines the language. This is a port of it (GCC 15:
 * {@code regex_scanner.tcc}, {@code regex_compiler.tcc}, {@code regex_automaton.tcc} and the
 * depth-first {@code regex_executor.tcc}), by way of fix's Zig port, which was fuzzed against
 * libstdc++. It builds the same NFA and searches it in the same order, so the same patterns are
 * rejected ({@code \d}, {@code a{,2}}, {@code [z-a]}, ranges into bytes >= 0x80, which compare as
 * signed chars), {@code *?} is two quantifiers, and the same capture groups come out, including
 * where that isn't POSIX's leftmost-longest rule.
 *
 * <p>Strings are Nix byte strings (one char per byte). The executor keeps its own stack rather
 * than recursing per character.
 */
final class Regex {
    private static final Map<String, Regex> CACHE = new ConcurrentHashMap<>();

    /** {@code _GLIBCXX_REGEX_STATE_LIMIT}. */
    private static final int STATE_LIMIT = 100000;
    /** Groups nest by recursion in the compiler; deeper patterns count as too large. */
    private static final int MAX_GROUP_DEPTH = 1000;
    private static final int NO_STATE = -1;

    // Opcodes.
    private static final int ALTERNATIVE = 0;
    private static final int REPEAT = 1;
    private static final int SUBEXPR_BEGIN = 2;
    private static final int SUBEXPR_END = 3;
    private static final int LINE_BEGIN = 4;
    private static final int LINE_END = 5;
    private static final int MATCH = 6;
    private static final int ACCEPT = 7;
    private static final int DUMMY = 8;

    private final int[] op;
    private final int[] next;
    private final int[] alt;
    private final int[] index;
    private final long[][] sets;
    /** Groups including the whole match (group 0). */
    private final int subCount;

    private Regex(Compiler c) {
        int n = c.states.size();
        op = new int[n];
        next = new int[n];
        alt = new int[n];
        index = new int[n];
        for (int i = 0; i < n; i++) {
            State s = c.states.get(i);
            op[i] = s.op;
            next[i] = s.next;
            alt[i] = s.alt;
            index[i] = s.index;
        }
        sets = c.sets.toArray(new long[0][]);
        subCount = c.subexprCount;
    }

    /** A match: its offsets and the capture groups (null for groups that didn't take part). */
    record Match(int start, int end, List<String> groups) {}

    private static final class InvalidRegex extends Exception {
        final boolean tooLarge;

        InvalidRegex(boolean tooLarge) {
            super(null, null, false, false);
            this.tooLarge = tooLarge;
        }
    }

    static Regex compile(String re) {
        Regex cached = CACHE.get(re);
        if (cached != null) return cached;
        try {
            Compiler c = new Compiler(re);
            c.compile();
            Regex r = new Regex(c);
            CACHE.put(re, r);
            return r;
        } catch (InvalidRegex e) {
            throw NixException.error((e.tooLarge ? "memory limit exceeded by regular expression '" : "invalid regular expression '") + re + "'", null);
        }
    }

    /** {@code std::regex_match}: the groups if the whole string matches, else null. */
    List<String> matchFull(String text) {
        Executor ex = new Executor(this, text, 0, false, false, false);
        if (!ex.run(true)) return null;
        return ex.result().groups;
    }

    /** The matches {@code std::regex_iterator} visits, as {@code builtins.split} uses them. */
    List<Match> findAll(String text) {
        List<Match> out = new ArrayList<>();
        Match last = null;
        boolean prevAvail = false;
        while (true) {
            Match m;
            if (last == null) {
                m = search(text, 0, false, false, false);
            } else {
                int start = last.end;
                m = null;
                boolean done = false;
                if (last.start == last.end) {
                    // After an empty match: a non-empty one at the same place, else move on.
                    if (start == text.length()) {
                        done = true;
                    } else {
                        m = search(text, start, prevAvail, true, true);
                        if (m == null) start++;
                    }
                }
                if (!done && m == null) {
                    prevAvail = true;
                    m = search(text, start, true, false, false);
                }
            }
            if (m == null) return out;
            out.add(m);
            last = m;
        }
    }

    /** {@code std::regex_search} from {@code start}. */
    private Match search(String text, int start, boolean prevAvail, boolean notNull, boolean continuous) {
        Executor ex = new Executor(this, text, start, prevAvail, notNull, continuous);
        return ex.search() ? ex.result() : null;
    }

    // ------------------------------------------------------------ scanner

    private enum Token {
        EOF, ORD_CHAR, ANYCHAR, LINE_BEGIN, LINE_END, CLOSURE0, CLOSURE1, OPT, OR, SUBEXPR_BEGIN, SUBEXPR_END,
        BRACKET_BEGIN, BRACKET_NEG_BEGIN, BRACKET_END, BRACKET_DASH, COLLSYMBOL, CHAR_CLASS_NAME,
        EQUIV_CLASS_NAME, INTERVAL_BEGIN, INTERVAL_END, DUP_COUNT, COMMA
    }

    /** {@code _M_extended_spec_char}: bytes with a meaning outside brackets, and the only ones a backslash may escape. */
    private static boolean isSpecial(char c) {
        return ".[\\()*+?{|^$".indexOf(c) >= 0;
    }

    private static final class Scanner {
        private static final int NORMAL = 0;
        private static final int IN_BRACKET = 1;
        private static final int IN_BRACE = 2;

        final String source;
        int pos;
        int state = NORMAL;
        boolean atBracketStart;
        Token token = Token.EOF;
        String value = "";

        Scanner(String source) {
            this.source = source;
        }

        void advance() throws InvalidRegex {
            if (pos == source.length()) {
                token = Token.EOF;
                return;
            }
            switch (state) {
                case NORMAL -> scanNormal();
                case IN_BRACKET -> scanInBracket();
                default -> scanInBrace();
            }
        }

        private void ordChar(int at) {
            token = Token.ORD_CHAR;
            value = source.substring(at, at + 1);
        }

        private void scanNormal() throws InvalidRegex {
            int at = pos;
            char c = source.charAt(at);
            pos++;
            if (!isSpecial(c)) {
                ordChar(at);
                return;
            }
            switch (c) {
                case '\\' -> {
                    // `_M_eat_escape_posix` with __STRICT_ANSI__: only special characters may be escaped.
                    if (pos == source.length() || !isSpecial(source.charAt(pos))) throw new InvalidRegex(false);
                    pos++;
                    ordChar(pos - 1);
                }
                case '(' -> token = Token.SUBEXPR_BEGIN;
                case ')' -> token = Token.SUBEXPR_END;
                case '[' -> {
                    state = IN_BRACKET;
                    atBracketStart = true;
                    if (pos < source.length() && source.charAt(pos) == '^') {
                        pos++;
                        token = Token.BRACKET_NEG_BEGIN;
                    } else {
                        token = Token.BRACKET_BEGIN;
                    }
                }
                case '{' -> {
                    state = IN_BRACE;
                    token = Token.INTERVAL_BEGIN;
                }
                case '^' -> token = Token.LINE_BEGIN;
                case '$' -> token = Token.LINE_END;
                case '.' -> token = Token.ANYCHAR;
                case '*' -> token = Token.CLOSURE0;
                case '+' -> token = Token.CLOSURE1;
                case '?' -> token = Token.OPT;
                case '|' -> token = Token.OR;
                default -> throw new IllegalStateException();
            }
        }

        private void scanInBracket() throws InvalidRegex {
            try {
                int at = pos;
                char c = source.charAt(at);
                pos++;
                switch (c) {
                    case '-' -> token = Token.BRACKET_DASH;
                    case '[' -> {
                        if (pos == source.length()) throw new InvalidRegex(false);
                        switch (source.charAt(pos)) {
                            case '.' -> token = Token.COLLSYMBOL;
                            case ':' -> token = Token.CHAR_CLASS_NAME;
                            case '=' -> token = Token.EQUIV_CLASS_NAME;
                            default -> {
                                ordChar(at);
                                return;
                            }
                        }
                        eatClass(source.charAt(pos));
                    }
                    case ']' -> {
                        if (atBracketStart) {
                            ordChar(at);
                        } else {
                            token = Token.BRACKET_END;
                            state = NORMAL;
                        }
                    }
                    default -> ordChar(at);
                }
            } finally {
                atBracketStart = false;
            }
        }

        /** The name in {@code [:name:]}, {@code [.name.]} or {@code [=name=]}. */
        private void eatClass(char delimiter) throws InvalidRegex {
            pos++;
            int start = pos;
            while (pos < source.length() && source.charAt(pos) != delimiter) pos++;
            value = source.substring(start, pos);
            if (pos + 1 >= source.length() || source.charAt(pos + 1) != ']') throw new InvalidRegex(false);
            pos += 2;
        }

        private void scanInBrace() throws InvalidRegex {
            int at = pos;
            char c = source.charAt(at);
            pos++;
            if (c >= '0' && c <= '9') {
                while (pos < source.length() && source.charAt(pos) >= '0' && source.charAt(pos) <= '9') pos++;
                token = Token.DUP_COUNT;
                value = source.substring(at, pos);
            } else if (c == ',') {
                token = Token.COMMA;
            } else if (c == '}') {
                state = NORMAL;
                token = Token.INTERVAL_END;
            } else {
                throw new InvalidRegex(false);
            }
        }
    }

    // ----------------------------------------------------------- compiler

    private static final class State {
        final int op;
        int next = NO_STATE;
        /** {@code ALTERNATIVE}: the left branch; {@code REPEAT}: the "once more" branch. */
        int alt = NO_STATE;
        /** Subexpressions: the group; {@code MATCH}: the byte set. */
        int index;

        State(int op) {
            this.op = op;
        }

        State copy() {
            State s = new State(op);
            s.next = next;
            s.alt = alt;
            s.index = index;
            return s;
        }

        boolean hasAlt() {
            return op == ALTERNATIVE || op == REPEAT;
        }
    }

    /** A sequence of states, {@code _StateSeq}. */
    private record Seq(int start, int end) {
        static Seq single(int id) {
            return new Seq(id, id);
        }
    }

    private static final class Compiler {
        final Scanner scanner;
        final List<State> states = new ArrayList<>();
        final List<long[]> sets = new ArrayList<>();
        final List<Seq> stack = new ArrayList<>();
        final List<Integer> parenStack = new ArrayList<>();
        int subexprCount;
        String value = "";
        int depth;

        Compiler(String source) {
            scanner = new Scanner(source);
        }

        void compile() throws InvalidRegex {
            scanner.advance();
            Seq r = Seq.single(0);
            r = append(r, insertSubexprBegin());
            disjunction();
            if (!matchToken(Token.EOF)) throw new InvalidRegex(false);
            r = appendSeq(r, pop());
            r = append(r, insertSubexprEnd());
            append(r, insert(new State(ACCEPT)));
            eliminateDummy();
        }

        boolean matchToken(Token token) throws InvalidRegex {
            if (scanner.token != token) return false;
            value = scanner.value;
            scanner.advance();
            return true;
        }

        int insert(State s) throws InvalidRegex {
            states.add(s);
            if (states.size() > STATE_LIMIT) throw new InvalidRegex(true);
            return states.size() - 1;
        }

        int insertSubexprBegin() throws InvalidRegex {
            int idx = subexprCount++;
            parenStack.add(idx);
            State s = new State(SUBEXPR_BEGIN);
            s.index = idx;
            return insert(s);
        }

        int insertSubexprEnd() throws InvalidRegex {
            State s = new State(SUBEXPR_END);
            s.index = parenStack.remove(parenStack.size() - 1);
            return insert(s);
        }

        void insertMatcher(long[] set) throws InvalidRegex {
            sets.add(set);
            State s = new State(MATCH);
            s.index = sets.size() - 1;
            push(Seq.single(insert(s)));
        }

        State at(int id) {
            return states.get(id);
        }

        Seq append(Seq seq, int id) {
            at(seq.end).next = id;
            return new Seq(seq.start, id);
        }

        Seq appendSeq(Seq seq, Seq other) {
            at(seq.end).next = other.start;
            return new Seq(seq.start, other.end);
        }

        void push(Seq seq) {
            stack.add(seq);
        }

        Seq pop() {
            return stack.remove(stack.size() - 1);
        }

        void disjunction() throws InvalidRegex {
            alternative();
            while (matchToken(Token.OR)) {
                Seq alt1 = pop();
                alternative();
                Seq alt2 = pop();
                int end = insert(new State(DUMMY));
                append(alt1, end);
                append(alt2, end);
                // The left alternative is `alt`, which the executor tries first.
                State a = new State(ALTERNATIVE);
                a.next = alt2.start;
                a.alt = alt1.start;
                push(new Seq(insert(a), end));
            }
        }

        /** A sequence of terms ending in a dummy state. */
        void alternative() throws InvalidRegex {
            int base = stack.size();
            while (term()) {
                // terms are pushed onto the stack
            }
            Seq tail = Seq.single(insert(new State(DUMMY)));
            while (stack.size() > base) tail = appendSeq(pop(), tail);
            push(tail);
        }

        boolean term() throws InvalidRegex {
            if (matchToken(Token.LINE_BEGIN)) {
                push(Seq.single(insert(new State(LINE_BEGIN))));
                return true;
            }
            if (matchToken(Token.LINE_END)) {
                push(Seq.single(insert(new State(LINE_END))));
                return true;
            }
            if (!atom()) return false;
            while (quantifier()) {
                // repeat
            }
            return true;
        }

        private int repeatState(int alt) throws InvalidRegex {
            State s = new State(REPEAT);
            s.alt = alt;
            return insert(s);
        }

        boolean quantifier() throws InvalidRegex {
            if (matchToken(Token.CLOSURE0)) {
                Seq e = pop();
                Seq r = Seq.single(repeatState(e.start));
                appendSeq(e, r);
                push(r);
            } else if (matchToken(Token.CLOSURE1)) {
                Seq e = pop();
                push(append(e, repeatState(e.start)));
            } else if (matchToken(Token.OPT)) {
                Seq e = pop();
                int end = insert(new State(DUMMY));
                Seq r = Seq.single(repeatState(e.start));
                append(e, end);
                r = append(r, end);
                push(r);
            } else if (matchToken(Token.INTERVAL_BEGIN)) {
                if (!matchToken(Token.DUP_COUNT)) throw new InvalidRegex(false);
                Seq r = pop();
                Seq e = Seq.single(insert(new State(DUMMY)));
                int minRep = curIntValue();
                boolean infinite = false;
                long n = 0;
                if (matchToken(Token.COMMA)) {
                    if (matchToken(Token.DUP_COUNT)) {
                        n = (long) curIntValue() - minRep;
                    } else {
                        infinite = true;
                    }
                }
                if (!matchToken(Token.INTERVAL_END)) throw new InvalidRegex(false);
                for (int i = 0; i < minRep; i++) e = appendSeq(e, clone(r));
                if (infinite) {
                    Seq tmp = clone(r);
                    Seq s = Seq.single(repeatState(tmp.start));
                    appendSeq(tmp, s);
                    e = appendSeq(e, s);
                } else {
                    if (n < 0) throw new InvalidRegex(false);
                    int end = insert(new State(DUMMY));
                    List<Integer> optional = new ArrayList<>();
                    for (long i = 0; i < n; i++) {
                        Seq tmp = clone(r);
                        State a = new State(REPEAT);
                        a.next = tmp.start;
                        a.alt = end;
                        int id = insert(a);
                        optional.add(id);
                        e = appendSeq(e, new Seq(id, tmp.end));
                    }
                    e = append(e, end);
                    // Built with "skip" as `alt`; the executor wants "once more" there.
                    for (int id : optional) {
                        State s = at(id);
                        int t = s.next;
                        s.next = s.alt;
                        s.alt = t;
                    }
                }
                push(e);
            } else {
                return false;
            }
            return true;
        }

        /** {@code _M_cur_int_value(10)}, which rejects counts that overflow an int. */
        int curIntValue() throws InvalidRegex {
            int v = 0;
            for (int i = 0; i < value.length(); i++) {
                try {
                    v = Math.addExact(Math.multiplyExact(v, 10), value.charAt(i) - '0');
                } catch (ArithmeticException e) {
                    throw new InvalidRegex(false);
                }
            }
            return v;
        }

        /**
         * {@code _StateSeq::_M_clone}, keeping its traversal order: a state reached twice before
         * it's copied is copied twice, which counts towards the state limit.
         */
        Seq clone(Seq seq) throws InvalidRegex {
            Map<Integer, Integer> map = new HashMap<>();
            List<Integer> todo = new ArrayList<>();
            todo.add(seq.start);
            while (!todo.isEmpty()) {
                int u = todo.remove(todo.size() - 1);
                State dup = at(u).copy();
                map.put(u, insert(dup));
                if (dup.hasAlt() && dup.alt != NO_STATE && !map.containsKey(dup.alt)) todo.add(dup.alt);
                if (u == seq.end) continue;
                if (dup.next != NO_STATE && !map.containsKey(dup.next)) todo.add(dup.next);
            }
            for (int id : map.values()) {
                State s = at(id);
                if (s.next != NO_STATE) s.next = map.getOrDefault(s.next, s.next);
                if (s.hasAlt() && s.alt != NO_STATE) s.alt = map.getOrDefault(s.alt, s.alt);
            }
            return new Seq(map.get(seq.start), map.get(seq.end));
        }

        void eliminateDummy() {
            for (State s : states) {
                while (s.next >= 0 && at(s.next).op == DUMMY) s.next = at(s.next).next;
                if (s.hasAlt()) {
                    while (s.alt >= 0 && at(s.alt).op == DUMMY) s.alt = at(s.alt).next;
                }
            }
        }

        boolean atom() throws InvalidRegex {
            if (matchToken(Token.ANYCHAR)) {
                // POSIX `.`: anything but NUL.
                long[] set = {-1L, -1L, -1L, -1L};
                set[0] &= ~1L;
                insertMatcher(set);
            } else if (matchToken(Token.ORD_CHAR)) {
                long[] set = new long[4];
                setBit(set, value.charAt(0));
                insertMatcher(set);
            } else if (matchToken(Token.SUBEXPR_BEGIN)) {
                if (depth == MAX_GROUP_DEPTH) throw new InvalidRegex(true);
                depth++;
                try {
                    Seq r = Seq.single(insertSubexprBegin());
                    disjunction();
                    if (!matchToken(Token.SUBEXPR_END)) throw new InvalidRegex(false);
                    r = appendSeq(r, pop());
                    r = append(r, insertSubexprEnd());
                    push(r);
                } finally {
                    depth--;
                }
            } else {
                boolean negated = matchToken(Token.BRACKET_NEG_BEGIN);
                if (!negated && !matchToken(Token.BRACKET_BEGIN)) return false;
                insertMatcher(bracketExpression(negated));
            }
            return true;
        }

        /** {@code _M_insert_bracket_matcher} and {@code _M_expression_term}. */
        long[] bracketExpression(boolean negated) throws InvalidRegex {
            Bracket matcher = new Bracket();
            // The last single character, which may yet start a range: -1 none, -2 a class.
            int last = -1;
            if (matchToken(Token.ORD_CHAR)) {
                last = value.charAt(0);
            } else if (matchToken(Token.BRACKET_DASH)) {
                last = '-';
            }
            while (true) {
                if (matchToken(Token.BRACKET_END)) break;
                if (matchToken(Token.COLLSYMBOL)) {
                    int c = lookupCollateName(value);
                    if (c < 0) throw new InvalidRegex(false);
                    setBit(matcher.chars, c);
                    if (last >= 0) setBit(matcher.chars, last);
                    last = c;
                } else if (matchToken(Token.EQUIV_CLASS_NAME)) {
                    if (last >= 0) setBit(matcher.chars, last);
                    last = -2;
                    int c = lookupCollateName(value);
                    if (c < 0) throw new InvalidRegex(false);
                    setBit(matcher.equivalents, toLower(c));
                    matcher.hasEquivalents = true;
                } else if (matchToken(Token.CHAR_CLASS_NAME)) {
                    if (last >= 0) setBit(matcher.chars, last);
                    last = -2;
                    int cls = lookupClassName(value);
                    if (cls < 0) throw new InvalidRegex(false);
                    matcher.classes |= cls;
                } else if (matchToken(Token.ORD_CHAR)) {
                    if (last >= 0) setBit(matcher.chars, last);
                    last = value.charAt(0);
                } else if (matchToken(Token.BRACKET_DASH)) {
                    if (matchToken(Token.BRACKET_END)) {
                        // A dash before `]` is a character.
                        if (last >= 0) setBit(matcher.chars, last);
                        last = '-';
                        break;
                    }
                    // A dash may only follow a range's start, or begin the expression.
                    if (last < 0) throw new InvalidRegex(false);
                    int end;
                    if (matchToken(Token.ORD_CHAR)) {
                        end = value.charAt(0);
                    } else if (matchToken(Token.BRACKET_DASH)) {
                        end = '-';
                    } else {
                        throw new InvalidRegex(false);
                    }
                    matcher.addRange(last, end);
                    last = -1;
                } else {
                    throw new InvalidRegex(false);
                }
            }
            if (last >= 0) setBit(matcher.chars, last);
            return matcher.set(negated);
        }
    }

    private static void setBit(long[] set, int c) {
        set[c >> 6] |= 1L << (c & 63);
    }

    private static boolean isSet(long[] set, int c) {
        return (set[c >> 6] & (1L << (c & 63))) != 0;
    }

    /** {@code _BracketMatcher} in the classic "C" locale. */
    private static final class Bracket {
        final long[] chars = new long[4];
        final List<int[]> ranges = new ArrayList<>();
        int classes;
        /** Lowercased: {@code transform_primary} folds case. */
        final long[] equivalents = new long[4];
        boolean hasEquivalents;

        /** Ranges compare chars, which are signed: {@code [a-é]} is backwards. */
        void addRange(int first, int last) throws InvalidRegex {
            if ((byte) first > (byte) last) throw new InvalidRegex(false);
            ranges.add(new int[] {first, last});
        }

        long[] set(boolean negated) {
            long[] out = new long[4];
            for (int c = 0; c < 256; c++) if (matches(c) != negated) setBit(out, c);
            return out;
        }

        boolean matches(int c) {
            if (isSet(chars, c)) return true;
            for (int[] r : ranges) if ((byte) r[0] <= (byte) c && (byte) c <= (byte) r[1]) return true;
            if (isClass(c, classes)) return true;
            return hasEquivalents && isSet(equivalents, toLower(c));
        }
    }

    private static final int CLASS_ALNUM = 1;
    private static final int CLASS_ALPHA = 1 << 1;
    private static final int CLASS_BLANK = 1 << 2;
    private static final int CLASS_CNTRL = 1 << 3;
    private static final int CLASS_DIGIT = 1 << 4;
    private static final int CLASS_GRAPH = 1 << 5;
    private static final int CLASS_LOWER = 1 << 6;
    private static final int CLASS_PRINT = 1 << 7;
    private static final int CLASS_PUNCT = 1 << 8;
    private static final int CLASS_SPACE = 1 << 9;
    private static final int CLASS_UPPER = 1 << 10;
    private static final int CLASS_XDIGIT = 1 << 11;
    private static final int CLASS_UNDERSCORE = 1 << 12;

    /** {@code regex_traits::lookup_classname}, which ignores case and knows d, w and s too. */
    private static int lookupClassName(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "d", "digit" -> CLASS_DIGIT;
            case "w" -> CLASS_ALNUM | CLASS_UNDERSCORE;
            case "s", "space" -> CLASS_SPACE;
            case "alnum" -> CLASS_ALNUM;
            case "alpha" -> CLASS_ALPHA;
            case "blank" -> CLASS_BLANK;
            case "cntrl" -> CLASS_CNTRL;
            case "graph" -> CLASS_GRAPH;
            case "lower" -> CLASS_LOWER;
            case "print" -> CLASS_PRINT;
            case "punct" -> CLASS_PUNCT;
            case "upper" -> CLASS_UPPER;
            case "xdigit" -> CLASS_XDIGIT;
            default -> -1;
        };
    }

    private static boolean isAlpha(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static int toLower(int c) {
        return c >= 'A' && c <= 'Z' ? c + 32 : c;
    }

    /** {@code regex_traits::isctype} in the "C" locale: ASCII only. */
    private static boolean isClass(int c, int classes) {
        if (classes == 0) return false;
        boolean alnum = isAlpha(c) || isDigit(c);
        return (classes & CLASS_ALNUM) != 0 && alnum
                || (classes & CLASS_ALPHA) != 0 && isAlpha(c)
                || (classes & CLASS_BLANK) != 0 && (c == ' ' || c == '\t')
                || (classes & CLASS_CNTRL) != 0 && (c < 0x20 || c == 0x7f)
                || (classes & CLASS_DIGIT) != 0 && isDigit(c)
                || (classes & CLASS_GRAPH) != 0 && c > 0x20 && c < 0x7f
                || (classes & CLASS_LOWER) != 0 && c >= 'a' && c <= 'z'
                || (classes & CLASS_PRINT) != 0 && c >= 0x20 && c < 0x7f
                || (classes & CLASS_PUNCT) != 0 && c > 0x20 && c < 0x7f && !alnum
                || (classes & CLASS_SPACE) != 0 && (c == ' ' || c >= '\t' && c <= '\r')
                || (classes & CLASS_UPPER) != 0 && c >= 'A' && c <= 'Z'
                || (classes & CLASS_XDIGIT) != 0 && (isDigit(c) || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')
                || (classes & CLASS_UNDERSCORE) != 0 && c == '_';
    }

    private static final String[] COLLATE_NAMES = {
        "NUL", "SOH", "STX", "ETX", "EOT", "ENQ", "ACK", "alert", "backspace", "tab", "newline", "vertical-tab",
        "form-feed", "carriage-return", "SO", "SI", "DLE", "DC1", "DC2", "DC3", "DC4", "NAK", "SYN", "ETB", "CAN",
        "EM", "SUB", "ESC", "IS4", "IS3", "IS2", "IS1", "space", "exclamation-mark", "quotation-mark",
        "number-sign", "dollar-sign", "percent-sign", "ampersand", "apostrophe", "left-parenthesis",
        "right-parenthesis", "asterisk", "plus-sign", "comma", "hyphen", "period", "slash", "zero", "one", "two",
        "three", "four", "five", "six", "seven", "eight", "nine", "colon", "semicolon", "less-than-sign",
        "equals-sign", "greater-than-sign", "question-mark", "commercial-at", "A", "B", "C", "D", "E", "F", "G",
        "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "left-square-bracket", "backslash", "right-square-bracket", "circumflex", "underscore", "grave-accent",
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u",
        "v", "w", "x", "y", "z", "left-curly-bracket", "vertical-line", "right-curly-bracket", "tilde", "DEL",
    };

    /** {@code regex_traits::lookup_collatename}: the POSIX names of the ASCII characters. */
    private static int lookupCollateName(String name) {
        for (int c = 0; c < COLLATE_NAMES.length; c++) if (COLLATE_NAMES[c].equals(name)) return c;
        return -1;
    }

    // ----------------------------------------------------------- executor

    // Tasks on the executor's stack: the recursive executor's calls, and what each undoes when
    // it returns. Encoded as (kind, a, b, c) quadruples of ints.
    private static final int T_VISIT = 0;
    private static final int T_RESTORE_FIRST = 1;
    private static final int T_RESTORE_SUB = 2;
    private static final int T_RESTORE_CURRENT = 3;
    private static final int T_RESTORE_REP = 4;
    private static final int T_DECREMENT_REP = 5;
    /** After a repetition's "once more" branch: its "done" branch, unless that found a match. */
    private static final int T_REPEAT_NEXT = 6;
    /** After an alternative's left branch: its right one. */
    private static final int T_ALTERNATIVE_NEXT = 7;
    private static final int T_ALTERNATIVE_MERGE = 8;

    private static final class Executor {
        final Regex re;
        final String text;
        int begin;
        int current;
        boolean prevAvail;
        final boolean notNull;
        final boolean continuous;
        // Sub-matches: first, second, matched (as 0/1), three ints per group.
        final int[] curResults;
        final int[] results;
        final int[] repPos;
        final int[] repCount;
        boolean hasSol;
        int solPos = -1;
        int[] tasks = new int[64];
        int top;

        Executor(Regex re, String text, int begin, boolean prevAvail, boolean notNull, boolean continuous) {
            this.re = re;
            this.text = text;
            this.begin = begin;
            this.prevAvail = prevAvail;
            this.notNull = notNull;
            this.continuous = continuous;
            curResults = new int[re.subCount * 3];
            results = new int[re.subCount * 3];
            repPos = new int[re.op.length];
            repCount = new int[re.op.length];
        }

        Match result() {
            List<String> groups = new ArrayList<>();
            for (int g = 1; g < re.subCount; g++) {
                groups.add(results[g * 3 + 2] != 0 ? text.substring(results[g * 3], results[g * 3 + 1]) : null);
            }
            return new Match(results[0], results[1], groups);
        }

        /** {@code _M_search}: from {@code begin}, then (unless continuous) each later position. */
        boolean search() {
            if (run(false)) return true;
            if (continuous) return false;
            prevAvail = true;
            while (begin != text.length()) {
                begin++;
                if (run(false)) return true;
            }
            return false;
        }

        private void push(int kind, int a, int b, int c) {
            if (top + 4 > tasks.length) tasks = Arrays.copyOf(tasks, tasks.length * 2);
            tasks[top++] = kind;
            tasks[top++] = a;
            tasks[top++] = b;
            tasks[top++] = c;
        }

        /** {@code _M_main_dispatch}; {@code exact} for regex_match, else a prefix match. */
        boolean run(boolean exact) {
            current = begin;
            hasSol = false;
            solPos = -1;
            System.arraycopy(results, 0, curResults, 0, results.length);
            top = 0;
            push(T_VISIT, 0, 0, 0);
            while (top > 0) {
                top -= 4;
                int kind = tasks[top];
                int a = tasks[top + 1];
                int b = tasks[top + 2];
                int c = tasks[top + 3];
                switch (kind) {
                    case T_VISIT -> visit(exact, a);
                    case T_RESTORE_FIRST -> curResults[a * 3] = b;
                    case T_RESTORE_SUB -> {
                        curResults[a * 3] = b;
                        curResults[a * 3 + 1] = c >> 1;
                        curResults[a * 3 + 2] = c & 1;
                    }
                    case T_RESTORE_CURRENT -> current = a;
                    case T_RESTORE_REP -> {
                        repPos[a] = b;
                        repCount[a] = c;
                    }
                    case T_DECREMENT_REP -> repCount[a]--;
                    case T_REPEAT_NEXT -> {
                        if (!hasSol) push(T_VISIT, re.next[a], 0, 0);
                    }
                    case T_ALTERNATIVE_NEXT -> {
                        // POSIX: try the right branch too, and keep a longer match.
                        push(T_ALTERNATIVE_MERGE, hasSol ? 1 : 0, 0, 0);
                        hasSol = false;
                        push(T_VISIT, re.next[a], 0, 0);
                    }
                    case T_ALTERNATIVE_MERGE -> hasSol = hasSol || a != 0;
                    default -> throw new IllegalStateException();
                }
            }
            return hasSol;
        }

        private void visit(boolean exact, int i) {
            if (i < 0) return;
            switch (re.op[i]) {
                case REPEAT -> {
                    // Greedy: once more first.
                    push(T_REPEAT_NEXT, i, 0, 0);
                    repOnceMore(i);
                }
                case SUBEXPR_BEGIN -> {
                    int g = re.index[i];
                    push(T_RESTORE_FIRST, g, curResults[g * 3], 0);
                    curResults[g * 3] = current;
                    push(T_VISIT, re.next[i], 0, 0);
                }
                case SUBEXPR_END -> {
                    int g = re.index[i];
                    // The whole sub-match is restored (second and matched packed together).
                    push(T_RESTORE_SUB, g, curResults[g * 3], curResults[g * 3 + 1] * 2 + curResults[g * 3 + 2]);
                    curResults[g * 3 + 1] = current;
                    curResults[g * 3 + 2] = 1;
                    push(T_VISIT, re.next[i], 0, 0);
                }
                case LINE_BEGIN -> {
                    if (current == begin && !prevAvail) push(T_VISIT, re.next[i], 0, 0);
                }
                case LINE_END -> {
                    if (current == text.length()) push(T_VISIT, re.next[i], 0, 0);
                }
                case MATCH -> {
                    if (current == text.length()) return;
                    if (!isSet(re.sets[re.index[i]], text.charAt(current) & 0xff)) return;
                    push(T_RESTORE_CURRENT, current, 0, 0);
                    current++;
                    push(T_VISIT, re.next[i], 0, 0);
                }
                case ACCEPT -> accept(exact);
                case ALTERNATIVE -> {
                    push(T_ALTERNATIVE_NEXT, i, 0, 0);
                    push(T_VISIT, re.alt[i], 0, 0);
                }
                case DUMMY -> push(T_VISIT, re.next[i], 0, 0);
                default -> throw new IllegalStateException();
            }
        }

        /** {@code _M_rep_once_more}: going round without consuming anything is allowed twice in a row. */
        private void repOnceMore(int i) {
            int alt = re.alt[i];
            if (repCount[i] == 0 || repPos[i] != current) {
                push(T_RESTORE_REP, i, repPos[i], repCount[i]);
                repPos[i] = current;
                repCount[i] = 1;
                push(T_VISIT, alt, 0, 0);
            } else if (repCount[i] < 2) {
                push(T_DECREMENT_REP, i, 0, 0);
                repCount[i]++;
                push(T_VISIT, alt, 0, 0);
            }
        }

        private void accept(boolean exact) {
            hasSol = !exact || current == text.length();
            if (current == begin && notNull) hasSol = false;
            if (!hasSol) return;
            // POSIX: a later solution replaces an earlier one only if it's longer.
            if (solPos < 0 || solPos < current) {
                solPos = current;
                System.arraycopy(curResults, 0, results, 0, results.length);
            }
        }
    }
}
