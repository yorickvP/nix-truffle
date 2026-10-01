package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;
import nixtruffle.NixContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Evaluation on more threads ({@code eval-cores}, like Determinate Nix's; 0 for all cores). Where
 * the main thread is about to force many values anyway (printing, {@code toJSON}, {@code
 * deepSeq}), it hands them to workers first ({@link #ahead}), and then goes through them in order,
 * finding them evaluated, or waiting for the worker that is evaluating one (see {@link Thunk}).
 *
 * <p>Workers evaluate without side effects: a worker that reaches one (a trace, a fetch, another
 * language: {@link #mainOnly}) gives up its task, which unwinds its thunks to pending, and the main
 * thread evaluates them in order. So does a worker that hits an error: the main thread gets the
 * same error, in the order a sequential evaluation would.
 */
public final class Parallel {
    private final NixContext ctx;
    private final int workers;
    /** A value to evaluate, at the call depth the main thread would evaluate it at. */
    private record Task(Thunk thunk, int callDepth) {}

    private final LinkedBlockingDeque<Task> tasks = new LinkedBlockingDeque<>();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean stopped;

    public Parallel(NixContext ctx, int workers) {
        this.ctx = ctx;
        this.workers = workers;
    }

    /** A worker gives up a task: it reached something only the main thread may do. */
    public static final class Abandon extends AbstractTruffleException {
        public Abandon() {
            super("abandoned");
        }
    }

    /** At a side effect ({@code what}): a worker gives up its task here. */
    public static void mainOnly(Node location, String what) {
        if (!EvalThread.SINGLE_THREADED.isValid() && EvalThread.current(location).worker) {
            if (STATS != null && STATS.computeIfAbsent("gave up at " + what, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet() == 1) {
                StackTraceElement[] st = new Throwable().getStackTrace();
                StringBuilder sb = new StringBuilder("parallel: first give-up at " + what + ":");
                for (int i = 1; i < Math.min(st.length, 40); i++) if (st[i].getClassName().startsWith("nixtruffle")) sb.append("\n    ").append(st[i]);
                System.err.println(sb);
            }
            throw new Abandon();
        }
    }

    /** -Dnixtruffle.parallelStats=true: what workers did, printed at exit. */
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> STATS =
            Boolean.getBoolean("nixtruffle.parallelStats") ? new java.util.concurrent.ConcurrentHashMap<>() : null;

    static {
        if (STATS != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> new java.util.TreeMap<>(STATS).forEach((k, v) -> System.err.println("parallel: " + k + ": " + v))));
        }
    }

    private static void count(String what) {
        if (STATS != null) STATS.computeIfAbsent(what, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
    }

    private static final java.util.concurrent.locks.ReentrantLock FETCH = new java.util.concurrent.locks.ReentrantLock();
    /** The lock is held by code that may evaluate (getFlake), so that it may wait for other threads. */
    private static volatile boolean fetchEvaluates;

    /**
     * Runs fetcher code, which isn't thread-safe, one thread at a time. Fetches evaluate their
     * arguments before, so a thread that holds the lock for one doesn't wait for other threads;
     * {@code evaluates} code ({@code getFlake}) may, so a worker that would wait for it gives up its
     * task instead (and the main thread, which then waits for it, never waits for a worker that
     * waits for it).
     */
    public static <T> T fetching(boolean evaluates, java.util.function.Supplier<T> body) {
        if (EvalThread.SINGLE_THREADED.isValid()) return body.get();
        if (EvalThread.current(null).worker) {
            if (evaluates) throw abandon("getFlake");
            try {
                while (!FETCH.tryLock(10, TimeUnit.MILLISECONDS)) {
                    if (fetchEvaluates) throw abandon("a fetch, during getFlake");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Abandon();
            }
        } else {
            FETCH.lock();
        }
        boolean outer = !fetchEvaluates && evaluates;
        if (outer) fetchEvaluates = true;
        try {
            return body.get();
        } finally {
            if (outer) fetchEvaluates = false;
            FETCH.unlock();
        }
    }

    private static Abandon abandon(String what) {
        count("gave up at " + what);
        return new Abandon();
    }

    /**
     * Has idle workers evaluate the values (to weak head normal form) that aren't yet: the parts of
     * the result of {@code nix eval} that the main thread is about to print (not yet in builtins
     * like {@code toJSON} inside the evaluation, whose parts are mostly small). The main thread
     * goes through them from the front, and the workers take them from the back.
     */
    @TruffleBoundary
    public static void ahead(Object[] values) {
        if (values.length < 2) return;
        NixContext ctx = NixContext.get(null);
        Parallel p = ctx.parallel;
        if (p != null && Thread.currentThread() == ctx.mainThread) p.submit(values);
    }

    private synchronized void submit(Object[] values) {
        if (stopped) return;
        if (threads.isEmpty()) start();
        // The printer forces each value a level deeper (see CallDepth).
        int depth = ctx.main.callDepth + 1;
        for (Object v : values) {
            if (v instanceof Thunk t && !t.isDone() && !t.isRunning()) tasks.add(new Task(t, depth));
        }
    }

    private void start() {
        EvalThread.SINGLE_THREADED.invalidate();
        for (int i = 0; i < workers; i++) {
            Thread t = ctx.env.newTruffleThreadBuilder(this::work).stackSize(Long.getLong("nixtruffle.stackMb", 128) << 20).build();
            t.setName("nix-eval-worker-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
    }

    private void work() {
        EvalThread self = EvalThread.current(null);
        self.worker = true;
        while (!stopped) {
            Task t;
            try {
                t = tasks.pollLast(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (t == null) continue;
            count("tasks");
            self.callDepth = t.callDepth();
            try {
                t.thunk().forceSlow();
                count("tasks done");
            } catch (Abandon e) {
                count("tasks given up");
            } catch (AbstractTruffleException | StackOverflowError e) {
                // An error: the main thread will find it.
                count("tasks failed: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
            }
        }
    }

    /** When the context closes. */
    public void stop() {
        synchronized (this) {
            stopped = true;
        }
        for (Thread t : threads) {
            t.interrupt();
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
