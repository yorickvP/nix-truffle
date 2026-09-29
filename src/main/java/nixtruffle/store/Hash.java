package nixtruffle.store;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** A hash value plus Nix's encodings of it (base16, Nix base32, base64, SRI). */
public record Hash(String algo, byte[] bytes) {
    static final String BASE32_CHARS = "0123456789abcdfghijklmnpqrsvwxyz";

    public static int size(String algo) {
        return switch (algo) {
            case "md5" -> 16;
            case "sha1" -> 20;
            case "sha256" -> 32;
            case "sha512" -> 64;
            default -> throw new IllegalArgumentException("unknown hash algorithm '" + algo + "'");
        };
    }

    public static boolean isAlgo(String algo) {
        return switch (algo) {
            case "md5", "sha1", "sha256", "sha512" -> true;
            default -> false;
        };
    }

    public static MessageDigest digest(String algo) {
        try {
            return MessageDigest.getInstance(switch (algo) {
                case "md5" -> "MD5";
                case "sha1" -> "SHA-1";
                case "sha256" -> "SHA-256";
                case "sha512" -> "SHA-512";
                default -> throw new IllegalArgumentException("unknown hash algorithm '" + algo + "'");
            });
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Hash of(String algo, byte[] data) {
        return new Hash(algo, digest(algo).digest(data));
    }

    public static Hash of(String algo, String data) {
        return of(algo, nixtruffle.runtime.Bytes.get(data));
    }

    public static Hash sha256(String data) {
        return of("sha256", data);
    }

    public String hex() {
        return HexFormat.of().formatHex(bytes);
    }

    /** {@code sha256:<hex>}, the form used inside store path fingerprints. */
    public String typedHex() {
        return algo + ":" + hex();
    }

    public String base32() {
        return base32(bytes);
    }

    public String base64() {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public String sri() {
        return algo + "-" + base64();
    }

    /** Nix's base32: its own alphabet, least significant bits first. */
    public static String base32(byte[] hash) {
        int len = (hash.length * 8 - 1) / 5 + 1;
        StringBuilder s = new StringBuilder(len);
        for (int n = len - 1; n >= 0; n--) {
            int b = n * 5;
            int i = b / 8;
            int j = b % 8;
            int c = (hash[i] & 0xff) >> j;
            if (i < hash.length - 1) c |= (hash[i + 1] & 0xff) << (8 - j);
            s.append(BASE32_CHARS.charAt(c & 0x1f));
        }
        return s.toString();
    }

    static byte[] decodeBase32(String s, int size) {
        byte[] hash = new byte[size];
        for (int n = 0; n < s.length(); n++) {
            char c = s.charAt(s.length() - n - 1);
            int digit = BASE32_CHARS.indexOf(c);
            if (digit < 0) throw new IllegalArgumentException("invalid base-32 hash '" + s + "'");
            int b = n * 5;
            int i = b / 8;
            int j = b % 8;
            hash[i] |= (byte) (digit << j);
            if (i < size - 1) {
                hash[i + 1] |= (byte) (digit >> (8 - j));
            } else if ((digit >> (8 - j)) != 0) {
                throw new IllegalArgumentException("invalid base-32 hash '" + s + "'");
            }
        }
        return hash;
    }

    /** XOR-folds a hash to {@code size} bytes (store path hashes are sha256 folded to 20 bytes). */
    public static byte[] compress(byte[] hash, int size) {
        byte[] out = new byte[size];
        for (int i = 0; i < hash.length; i++) out[i % size] ^= hash[i];
        return out;
    }

    /** {@code parseHashAlgo}: an error for unknown algorithms (and for BLAKE3, which is experimental). */
    public static String parseAlgo(String s) {
        if (s.equals("blake3")) throw new IllegalArgumentException("experimental Nix feature 'blake3-hashes' is disabled");
        if (!isAlgo(s)) throw new IllegalArgumentException("unknown hash algorithm '" + s + "', expect 'blake3', 'md5', 'sha1', 'sha256', or 'sha512'");
        return s;
    }

    /**
     * Port of CppNix's {@code Hash::parseAny}: {@code algo:rest} or SRI {@code algo-base64}, or a
     * bare hash when {@code algo} is given (base16, nix32 or base64, told apart by length). The
     * prefix, if any, must agree with {@code algo}.
     */
    public static Hash parseAny(String original, String algo) {
        String rest = original;
        boolean sri = false;
        String parsed = null;
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            parsed = parseAlgo(rest.substring(0, colon));
            rest = rest.substring(colon + 1);
        } else {
            int dash = rest.indexOf('-');
            if (dash >= 0) {
                parsed = parseAlgo(rest.substring(0, dash));
                rest = rest.substring(dash + 1);
                sri = true;
            }
        }
        if (parsed == null && algo == null) {
            throw new IllegalArgumentException("hash '" + original + "' does not include a type, nor is the type otherwise known from context");
        }
        if (parsed != null && algo != null && !parsed.equals(algo)) {
            throw new IllegalArgumentException("hash '" + original + "' should have type '" + algo + "'");
        }
        String a = parsed != null ? parsed : algo;
        int size = size(a);
        byte[] d;
        String format;
        if (sri) {
            d = decodeBase64(rest);
            format = "SRI";
        } else if (rest.length() == size * 2) {
            d = decodeBase16(rest);
            format = "base16";
        } else if (rest.length() == (size * 8 - 1) / 5 + 1) {
            d = decodeNix32(rest);
            format = "nix32";
        } else if (rest.length() == ((4 * size / 3) + 3 & ~3)) {
            d = decodeBase64(rest);
            format = "Base64";
        } else {
            throw new IllegalArgumentException("hash '" + rest + "' has wrong length for hash algorithm '" + a + "'");
        }
        // A decoding error leaves nothing decoded (CppNix swallows it), which fails the length check.
        if (d == null || d.length != size) {
            throw new IllegalArgumentException("invalid " + format + " hash '" + rest + "', length " + (d == null ? 0 : d.length) + " != expected length " + size);
        }
        return new Hash(a, d);
    }

    /** {@code newHashAllowEmpty}: an empty string is the all-zero hash of {@code algo}. */
    public static Hash parseAllowEmpty(String s, String algo) {
        if (s.isEmpty()) {
            if (algo == null) throw new IllegalArgumentException("empty hash requires explicit hash algorithm");
            return new Hash(algo, new byte[size(algo)]);
        }
        return parseAny(s, algo);
    }

    /** An SRI or {@code algo:} prefixed hash (the algorithm must be given by the string). */
    public static Hash parseAnyPrefixed(String s) {
        if (s.indexOf(':') < 0 && s.indexOf('-') < 0) throw new IllegalArgumentException("hash '" + s + "' does not include a type");
        return parseAny(s, null);
    }

    private static byte[] decodeBase16(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0 || s.charAt(2 * i) > 'f' || s.charAt(2 * i + 1) > 'f') return null;
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    /** CppNix's base64 decoder: stops at '=', skips newlines, no length checks. */
    private static byte[] decodeBase64(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int d = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            if (c == '\n') continue;
            int digit = chars.indexOf(c);
            if (digit < 0) return null;
            bits += 6;
            d = d << 6 | digit;
            if (bits >= 8) {
                out.write(d >> (bits - 8) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    /** CppNix's nix32 decoder: overflowing bits make the result longer (and so invalid). */
    private static byte[] decodeNix32(String s) {
        byte[] res = new byte[(s.length() * 5 + 7) / 8 + 1];
        int len = 0;
        for (int n = 0; n < s.length(); n++) {
            int digit = BASE32_CHARS.indexOf(s.charAt(s.length() - n - 1));
            if (digit < 0) return null;
            int b = n * 5;
            int i = b / 8;
            int j = b % 8;
            len = Math.max(len, i + 1);
            res[i] |= (byte) (digit << j);
            if ((digit >> (8 - j)) != 0) {
                len = Math.max(len, i + 2);
                res[i + 1] |= (byte) (digit >> (8 - j));
            }
        }
        return Arrays.copyOf(res, len);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Hash h && h.algo.equals(algo) && Arrays.equals(h.bytes, bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
