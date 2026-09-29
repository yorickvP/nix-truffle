package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.nio.charset.StandardCharsets;

/**
 * Nix strings are byte strings. We represent them as Java strings with one char per byte
 * (ISO-8859-1), so that {@code length}, {@code substring} and ordering work on bytes like in Nix,
 * any byte sequence survives (file names, {@code substring} of a multi-byte character), and Java's
 * compact strings still store them as one byte per char. Everything that crosses into Java text
 * (file APIs, interop, the terminal) goes through here.
 */
public final class Bytes {
    private Bytes() {}

    /** The Nix string of these bytes. */
    public static String of(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    public static String of(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.ISO_8859_1);
    }

    /** The bytes of a Nix string. */
    public static byte[] get(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** A Nix string holding the UTF-8 encoding of Java text. */
    @TruffleBoundary
    public static String fromJava(String text) {
        if (isAscii(text)) return text;
        return of(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Java text from a Nix string, decoding UTF-8 (invalid sequences become U+FFFD). */
    @TruffleBoundary
    public static String toJava(String s) {
        if (isAscii(s)) return s;
        return new String(get(s), StandardCharsets.UTF_8);
    }

    /**
     * The bytes to print for a message: chars up to 255 are Nix string bytes, anything above is
     * Java text that ended up in the message (e.g. from an exception) and is encoded as UTF-8.
     */
    @TruffleBoundary
    public static byte[] output(String s) {
        boolean wide = false;
        for (int i = 0; i < s.length() && !wide; i++) wide = s.charAt(i) > 0xff;
        if (!wide) return get(s);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0xff) {
                out.write(c);
            } else {
                int cp = Character.codePointAt(s, i);
                if (Character.isSupplementaryCodePoint(cp)) i++;
                out.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
            }
        }
        return out.toByteArray();
    }

    public static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 0x80) return false;
        return true;
    }

    /** Whether the bytes of {@code s} are valid UTF-8 (what JSON output requires). */
    @TruffleBoundary
    public static boolean isValidUtf8(String s) {
        return utf8Error(s) < 0;
    }

    /**
     * The index of the first byte of an invalid or incomplete UTF-8 sequence, or -1. Follows
     * nlohmann::json's validation: overlong forms, surrogates and code points above U+10FFFF are
     * invalid.
     */
    @TruffleBoundary
    public static int utf8Error(String s) {
        int n = s.length();
        for (int i = 0; i < n; ) {
            int b = s.charAt(i);
            if (b < 0x80) {
                i++;
                continue;
            }
            int len;
            int min;
            if (b >= 0xc2 && b <= 0xdf) {
                len = 2;
                min = 0x80;
            } else if (b >= 0xe0 && b <= 0xef) {
                len = 3;
                min = 0x800;
            } else if (b >= 0xf0 && b <= 0xf4) {
                len = 4;
                min = 0x10000;
            } else {
                return i;
            }
            if (i + len > n) return i;
            int cp = b & (0xff >> (len + 1));
            for (int k = 1; k < len; k++) {
                int c = s.charAt(i + k);
                if ((c & 0xc0) != 0x80) return i;
                cp = (cp << 6) | (c & 0x3f);
            }
            if (cp < min || cp > 0x10ffff || cp >= 0xd800 && cp <= 0xdfff) return i;
            i += len;
        }
        return -1;
    }

    /** Appends the UTF-8 encoding of a code point to a Nix string being built. */
    public static void appendUtf8(StringBuilder sb, int cp) {
        if (cp < 0x80) {
            sb.append((char) cp);
        } else if (cp < 0x800) {
            sb.append((char) (0xc0 | cp >> 6)).append((char) (0x80 | cp & 0x3f));
        } else if (cp < 0x10000) {
            sb.append((char) (0xe0 | cp >> 12)).append((char) (0x80 | cp >> 6 & 0x3f)).append((char) (0x80 | cp & 0x3f));
        } else {
            sb.append((char) (0xf0 | cp >> 18)).append((char) (0x80 | cp >> 12 & 0x3f))
              .append((char) (0x80 | cp >> 6 & 0x3f)).append((char) (0x80 | cp & 0x3f));
        }
    }
}
