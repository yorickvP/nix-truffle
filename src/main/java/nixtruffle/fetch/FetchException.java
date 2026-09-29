package nixtruffle.fetch;

/** An error while parsing, locking or fetching an input; messages are byte strings. */
public class FetchException extends RuntimeException {
    public FetchException(String message) {
        super(message, null, false, false);
    }

    public FetchException(String message, Throwable cause) {
        super(message, cause, false, false);
    }

    /** A malformed URL ({@code BadURL}), which some parsers catch to try another syntax. */
    public static final class BadUrl extends FetchException {
        public BadUrl(String message) {
            super(message);
        }
    }
}
