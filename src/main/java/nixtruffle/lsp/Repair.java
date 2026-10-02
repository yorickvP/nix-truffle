package nixtruffle.lsp;

import com.oracle.truffle.api.source.Source;
import nixtruffle.parser.Expr;
import nixtruffle.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * A text that doesn't parse, made to (as one being typed mostly is close to): where the parser
 * stops, small insertions ({@code ;}, {@code null;}, a closing bracket, ...) at the error, at its
 * line's start or after the token before it; the first that parses, else the one that gets
 * furthest, a few times. Then the rest of the text can be analysed (its names, unused bindings,
 * further syntax errors), with offsets mapped back by {@link #original}.
 */
final class Repair {
    private static final List<String> INSERTIONS = List.of(";", "null;", "null", "}", "]", ")", " in null", "\"", "''", "= null;", " then null else null");
    private static final int ROUNDS = 5;
    private static final int MAX_LENGTH = 300_000;

    final String text;
    final Expr root;
    /** The syntax errors on the way, at their offsets in the original text. */
    final List<int[]> errorOffsets = new ArrayList<>();
    final List<String> errorMessages = new ArrayList<>();
    /** The insertions, in order: at offsets in the text as it was then, of a length. */
    private final List<int[]> insertions;
    /** What the first insertion inserted (at an offset of the original text): for a quick fix. */
    String firstInsertion;

    private Repair(String text, Expr root, List<int[]> insertions) {
        this.text = text;
        this.root = root;
        this.insertions = insertions;
    }

    /** The repaired text and its tree, or null if a few insertions didn't make it parse. */
    static Repair of(String uri, String original, Parser.SyntaxError first) {
        if (original.length() > MAX_LENGTH) return null;
        String text = original;
        Parser.SyntaxError error = first;
        List<int[]> insertions = new ArrayList<>();
        List<Parser.SyntaxError> errors = new ArrayList<>();
        errors.add(first);
        String firstText = null;
        for (int round = 0; round < ROUNDS; round++) {
            String best = null;
            int[] bestInsertion = null;
            Parser.SyntaxError bestError = null;
            String bestText = null;
            // How far a candidate gets, doubled, plus one if the error there is another one: so
            // that `;` then `}` at the end of a file can do, though the error stays at the end.
            int bestScore = 2 * error.offset;
            for (int at : positions(text, error.offset)) {
                for (String insertion : INSERTIONS) {
                    String t = text.substring(0, at) + insertion + text.substring(at);
                    try {
                        Expr root = parse(uri, t);
                        insertions.add(new int[] {at, insertion.length()});
                        Repair r = new Repair(t, root, insertions);
                        r.firstInsertion = insertions.size() == 1 ? insertion : firstText;
                        // The k-th error was found after k insertions.
                        for (int k = 0; k < errors.size(); k++) r.addError(errors.get(k), k);
                        return r;
                    } catch (Parser.SyntaxError e) {
                        // How far it got, in the text before this insertion.
                        int progress = e.offset >= at + insertion.length() ? e.offset - insertion.length() : at;
                        int score = 2 * progress + (e.detail.equals(error.detail) ? 0 : 1);
                        if (score > bestScore) {
                            bestScore = score;
                            best = t;
                            bestInsertion = new int[] {at, insertion.length()};
                            bestError = e;
                            bestText = insertion;
                        }
                    }
                }
            }
            if (best == null) return null;
            text = best;
            if (insertions.isEmpty()) firstText = bestText;
            insertions.add(bestInsertion);
            error = bestError;
            errors.add(error);
        }
        return null;
    }

    /** Records an error found after {@code inserted} insertions (its offset is in that text). */
    private void addError(Parser.SyntaxError e, int inserted) {
        int offset = e.offset;
        for (int i = inserted - 1; i >= 0; i--) offset = back(offset, insertions.get(i));
        errorOffsets.add(new int[] {offset});
        errorMessages.add(e.detail);
    }

    private static int back(int offset, int[] insertion) {
        int at = insertion[0], length = insertion[1];
        return offset >= at + length ? offset - length : Math.min(offset, at);
    }

    /** An offset in the repaired text, in the original one (one in an insertion: where it was inserted). */
    int original(int offset) {
        for (int i = insertions.size() - 1; i >= 0; i--) offset = back(offset, insertions.get(i));
        return offset;
    }

    /** Where the first insertion is, in the original text. */
    int firstInsertionAt() {
        return insertions.getFirst()[0];
    }

    /** Whether an offset of the repaired text is in what was inserted. */
    boolean inserted(int offset) {
        for (int i = insertions.size() - 1; i >= 0; i--) {
            int[] ins = insertions.get(i);
            if (offset >= ins[0] && offset < ins[0] + ins[1]) return true;
            offset = back(offset, ins);
        }
        return false;
    }

    /** Where to try insertions for an error at {@code offset}: there, after the token before, at the end of the line before, at its line's start. */
    private static List<Integer> positions(String text, int offset) {
        List<Integer> out = new ArrayList<>();
        int e = Math.min(offset, text.length());
        out.add(e);
        int prev = e;
        while (prev > 0 && Character.isWhitespace(text.charAt(prev - 1))) prev--;
        if (prev < e) out.add(prev);
        int line = text.lastIndexOf('\n', Math.max(0, e - 1)) + 1;
        // the end of the line before (`a = used` missing its `;`), then this line's start
        int lineEnd = line;
        while (lineEnd > 0 && Character.isWhitespace(text.charAt(lineEnd - 1))) lineEnd--;
        if (lineEnd < line && lineEnd > 0 && !out.contains(lineEnd)) out.add(lineEnd);
        while (line < e && (text.charAt(line) == ' ' || text.charAt(line) == '\t')) line++;
        if (line < e && !out.contains(line)) out.add(line);
        return out;
    }

    private static Expr parse(String uri, String text) {
        return new Parser(Source.newBuilder("nix", text, uri).build()).parseFile();
    }
}
