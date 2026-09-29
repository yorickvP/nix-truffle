package nixtruffle.builtins;

import java.util.ArrayList;
import java.util.List;

/** Port of Nix's version splitting and comparison ({@code builtins.compareVersions}). */
final class Versions {
    private Versions() {}

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    /** Returns the next component starting at {@code pos[0]}, advancing it; "" at the end. */
    private static String next(String v, int[] pos) {
        int p = pos[0];
        while (p < v.length() && (v.charAt(p) == '.' || v.charAt(p) == '-')) p++;
        int start = p;
        if (p < v.length() && isDigit(v.charAt(p))) {
            while (p < v.length() && isDigit(v.charAt(p))) p++;
        } else {
            while (p < v.length() && !isDigit(v.charAt(p)) && v.charAt(p) != '.' && v.charAt(p) != '-') p++;
        }
        pos[0] = p;
        return v.substring(start, p);
    }

    /** {@code string2Int<int>}: only digit strings that fit a 32-bit int are numbers. */
    private static Long toInt(String c) {
        if (c.isEmpty() || !c.chars().allMatch(ch -> ch >= '0' && ch <= '9')) return null;
        try {
            return (long) Integer.parseInt(c);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean lessThan(String c1, String c2) {
        Long n1 = toInt(c1);
        Long n2 = toInt(c2);
        if (n1 != null && n2 != null) return n1 < n2;
        if (c1.isEmpty() && n2 != null) return true;
        if (c1.equals("pre") && !c2.equals("pre")) return true;
        if (c2.equals("pre")) return false;
        if (n2 != null) return true;
        if (n1 != null) return false;
        return c1.compareTo(c2) < 0;
    }

    static int compare(String v1, String v2) {
        int[] p1 = {0};
        int[] p2 = {0};
        while (p1[0] < v1.length() || p2[0] < v2.length()) {
            String c1 = next(v1, p1);
            String c2 = next(v2, p2);
            if (lessThan(c1, c2)) return -1;
            if (lessThan(c2, c1)) return 1;
        }
        return 0;
    }

    static List<Object> split(String v) {
        List<Object> out = new ArrayList<>();
        int[] p = {0};
        while (true) {
            String c = next(v, p);
            if (c.isEmpty()) break;
            out.add(c);
        }
        return out;
    }
}
