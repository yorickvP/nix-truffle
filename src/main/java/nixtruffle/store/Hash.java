package nixtruffle.store;

import java.nio.charset.StandardCharsets;
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
        return of(algo, data.getBytes(StandardCharsets.UTF_8));
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

    /**
     * Parses a hash in any of Nix's formats: SRI ({@code sha256-...}), {@code algo:rest}, or bare
     * base16/base32/base64 when {@code algo} is given. An empty string gives the all-zero hash.
     */
    public static Hash parse(String s, String algo) {
        String rest = s;
        boolean sri = false;
        int colon = s.indexOf(':');
        int dash = s.indexOf('-');
        if (colon > 0 && isAlgo(s.substring(0, colon))) {
            algo = s.substring(0, colon);
            rest = s.substring(colon + 1);
        } else if (dash > 0 && isAlgo(s.substring(0, dash))) {
            algo = s.substring(0, dash);
            rest = s.substring(dash + 1);
            sri = true;
        }
        if (algo == null || algo.isEmpty()) throw new IllegalArgumentException("hash '" + s + "' does not include a type");
        int size = size(algo);
        if (rest.isEmpty()) return new Hash(algo, new byte[size]);
        if (!sri && rest.length() == size * 2) return new Hash(algo, HexFormat.of().parseHex(rest));
        if (!sri && rest.length() == (size * 8 - 1) / 5 + 1) return new Hash(algo, decodeBase32(rest, size));
        byte[] decoded = Base64.getDecoder().decode(rest);
        if (decoded.length != size) throw new IllegalArgumentException("hash '" + s + "' has wrong length for hash type '" + algo + "'");
        return new Hash(algo, decoded);
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
