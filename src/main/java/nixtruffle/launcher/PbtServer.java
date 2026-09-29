package nixtruffle.launcher;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import nixtruffle.runtime.Bytes;
import org.graalvm.polyglot.Value;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@code nix-truffle --pbt-server}: the evaluation protocol of
 * <a href="https://github.com/yorickvP/nix-pbt">nix-pbt</a>, one long-lived session.
 *
 * <pre>
 *   request:  &lt;byte length&gt;\n&lt;expression&gt;
 *   response: ok &lt;byte length&gt;\n&lt;JSON&gt;          (the value, as builtins.toJSON prints it)
 *          |  error &lt;byte length&gt;\n&lt;message&gt;
 * </pre>
 *
 * Expressions are evaluated relative to the current directory. A Java exception (a bug in
 * nix-truffle rather than an evaluation error) is reported as an error saying so, which nix-pbt
 * counts as a crash.
 */
final class PbtServer {
    private PbtServer() {}

    static int run(Context.Builder builder) {
        InputStream in = new BufferedInputStream(System.in);
        OutputStream out = System.out;
        try (Context context = builder.build()) {
            Value internals = context.eval("nix", "__nixTruffle");
            Value evalBytes = internals.getMember("evalBytes");
            Value toJSON = internals.getMember("toJSON");
            while (true) {
                int len = readLength(in);
                if (len < 0) return 0;
                byte[] expr = in.readNBytes(len);
                if (expr.length != len) return 0;
                String status;
                byte[] body;
                try {
                    body = toJSON.execute(evalBytes.execute((Object) expr)).as(byte[].class);
                    status = "ok";
                } catch (PolyglotException e) {
                    status = "error";
                    if (e.isHostException() || e.isInternalError()) {
                        body = ("nix-truffle crashed. This is a bug: " + e + "\n" + stackTrace(e)).getBytes(StandardCharsets.UTF_8);
                    } else {
                        body = Bytes.output(Main.describe(e));
                    }
                } catch (StackOverflowError e) {
                    status = "error";
                    body = "error: stack overflow".getBytes(StandardCharsets.UTF_8);
                } catch (RuntimeException e) {
                    status = "error";
                    body = ("nix-truffle crashed. This is a bug: " + e + "\n" + stackTrace(e)).getBytes(StandardCharsets.UTF_8);
                }
                out.write((status + " " + body.length + "\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    /** The decimal length on a line of its own; -1 at end of input. */
    private static int readLength(InputStream in) throws IOException {
        int n = 0;
        boolean any = false;
        while (true) {
            int c = in.read();
            if (c < 0) return -1;
            if (c == '\n' && any) return n;
            if (c < '0' || c > '9') throw new IOException("bad request header");
            n = n * 10 + (c - '0');
            any = true;
        }
    }

    private static String stackTrace(Throwable e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }
}
