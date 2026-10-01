import org.graalvm.polyglot.Context;

import java.lang.management.ManagementFactory;
import javax.management.ObjectName;

/**
 * Evaluates an expression, then, with the context still open (so what it keeps, such as imported
 * files and flakes, is alive), collects garbage and prints the live heap and its biggest classes.
 * See bench/heap.sh.
 */
public class Heap {
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(null, () -> {
            try {
                run(args[0], Integer.parseInt(args[1]));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "nix-eval", 128L << 20);
        t.start();
        t.join();
        System.exit(0);
    }

    static void run(String expr, int classes) throws Exception {
        try (Context c = Context.newBuilder().allowAllAccess(true)
                .option("nix.Config", "experimental-features = nix-command flakes\npure-eval = false\n").option("nix.ReadOnly", "true").build()) {
            long t0 = System.nanoTime();
            String result = c.eval("nix", expr).toString();
            System.out.printf("%s%nevaluated in %.1f s%n", result.length() > 200 ? result.substring(0, 200) + "..." : result, (System.nanoTime() - t0) / 1e9);
            System.gc();
            System.gc();
            System.out.printf("live heap: %d MB%n", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20);
            String histogram = (String) ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "gcClassHistogram", new Object[] {null}, new String[] {String[].class.getName()});
            String[] lines = histogram.split("\n");
            for (int i = 0; i < Math.min(lines.length - 1, classes + 2); i++) System.out.println(lines[i]);
            System.out.println(lines[lines.length - 1]);
        }
    }
}
