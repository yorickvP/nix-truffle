package nixtruffle.lsp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The comment that documents a definition: an RFC 145 doc comment ({@code /** ... *}{@code /}, as
 * nixpkgs' lib has them) right before it, else the {@code #} lines right before it.
 */
final class DocComments {
    private DocComments() {}

    /** The doc comment before line {@code line} (1-based) of {@code file}, as markdown, or "". */
    static String before(String file, int line) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
        int i = Math.min(line - 1, lines.size()) - 1;
        while (i >= 0 && lines.get(i).isBlank()) i--;
        if (i < 0) return "";
        List<String> out = new ArrayList<>();
        if (lines.get(i).trim().endsWith("*/")) {
            int end = i;
            while (i >= 0 && !lines.get(i).contains("/**")) i--;
            if (i < 0) return "";
            for (int j = i; j <= end; j++) out.add(lines.get(j));
            out.set(0, out.getFirst().substring(out.getFirst().indexOf("/**") + 3));
            String last = out.getLast();
            out.set(out.size() - 1, last.substring(0, last.lastIndexOf("*/")));
        } else {
            while (i >= 0 && lines.get(i).trim().startsWith("#")) {
                String l = lines.get(i).trim().substring(1);
                out.add(l.startsWith(" ") ? l.substring(1) : l);
                i--;
            }
            Collections.reverse(out);
            return String.join("\n", out).strip();
        }
        return dedent(out);
    }

    /** The doc comment before the first definition {@code name = ...} in {@code file}, or "". */
    static String named(String file, String name) {
        try {
            List<String> lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
            java.util.regex.Pattern def = java.util.regex.Pattern.compile("^\\s*" + java.util.regex.Pattern.quote(name) + "\\s*=(?!=).*");
            for (int i = 0; i < lines.size(); i++) {
                if (def.matcher(lines.get(i)).matches()) {
                    String doc = before(file, i + 1);
                    if (!doc.isEmpty()) return doc;
                }
            }
        } catch (IOException | RuntimeException e) {
            // no doc
        }
        return "";
    }

    /** Lines without their common indentation (the first line's own text aside). */
    private static String dedent(List<String> lines) {
        int indent = Integer.MAX_VALUE;
        for (int k = 1; k < lines.size(); k++) {
            String l = lines.get(k);
            if (l.isBlank()) continue;
            int n = 0;
            while (n < l.length() && l.charAt(n) == ' ') n++;
            indent = Math.min(indent, n);
        }
        StringBuilder sb = new StringBuilder(lines.getFirst().strip());
        for (int k = 1; k < lines.size(); k++) {
            String l = lines.get(k);
            sb.append('\n').append(l.isBlank() ? "" : l.substring(Math.min(indent, l.length())));
        }
        return sb.toString().strip();
    }
}
