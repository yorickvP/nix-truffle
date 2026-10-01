package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;
import nixtruffle.NixContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Evaluation on more threads ({@code eval-cores}, like Determinate Nix's; 0 for all cores). Where
 * a thread is about to force many values anyway, it first offers them to workers ({@link #ahead}),
 * and then goes through them in order, finding them evaluated, or waiting for the worker that is
 * evaluating one (see {@link Thunk}). Those places are the printing of {@code nix eval}'s result,
 * and builtins that force all elements of a list or all applications of a function to them
 * ({@code concatLists}, {@code filter}, {@code concatMap}, string coercion, {@code
 * derivationStrict}, ...). In a NixOS configuration, that is where independent parts come
 * together: {@code warnings} concatenates a list per systemd service, and each derivation coerces
 * the derivations it depends on.
 *
 * <p>Workers evaluate without side effects out of order: a worker that reaches one (a fetch that
 * evaluates, another language: {@link #mainOnly}) gives up its task, which unwinds its thunks to
 * pending, and the thread that needs them evaluates them in order. So does a worker that hits an
 * error: the thread that needs the value gets the same error, in the order a sequential evaluation
 * would.
 */
public final class Parallel {
    private final NixContext ctx;
    private final int workers;

    /**
     * Values that a thread is about to force in order, at the call depth it forces them at. Workers
     * take them from the end ({@code end} counts down), the thread goes through them from the
     * front; the first one is its own.
     */
    private static final class Batch {
        final Object[] values;
        final int callDepth;
        final AtomicInteger end;

        Batch(Object[] values, int callDepth) {
            this.values = values;
            this.callDepth = callDepth;
            this.end = new AtomicInteger(values.length);
        }

        /** A value to evaluate, or null when there are none left. */
        Thunk claim() {
            for (int i; (i = end.decrementAndGet()) >= 1; ) {
                if (values[i] instanceof Thunk t && !t.isDone() && !t.isRunning()) return t;
            }
            return null;
        }
    }

    /** Workers take the oldest first: those are the biggest parts of the evaluation. */
    private final ConcurrentLinkedDeque<Batch> batches = new ConcurrentLinkedDeque<>();
    /** Workers waiting for a batch, woken by {@code signal}. */
    private final AtomicInteger idle = new AtomicInteger();
    private final Object signal = new Object();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean started;
    private volatile boolean stopped;
    /** Whether any context evaluates in parallel: until one does, {@link #ahead} only reads this. */
    private static volatile boolean enabled;

    /**
     * The threads for {@code eval-cores = 0} (the default): one per core, but at most one per
     * gigabyte the heap may grow to ({@code -Xmx}, or three quarters of the memory, see
     * bin/nix-truffle), since threads evaluating side by side keep more alive at once.
     */
    public static int defaultThreads() {
        long gigabytes = Runtime.getRuntime().maxMemory() >> 30;
        return (int) Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), gigabytes));
    }

    public Parallel(NixContext ctx, int workers) {
        this.ctx = ctx;
        this.workers = workers;
        enabled = true;
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
        add(what, 1);
    }

    static void add(String what, long n) {
        if (STATS != null) STATS.computeIfAbsent(what, k -> new java.util.concurrent.atomic.AtomicLong()).addAndGet(n);
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
     * Offers the values to idle workers, to evaluate to weak head normal form: the caller is about
     * to force them all, in order. It goes through them from the front, and workers take them from
     * the back.
     */
    public static void ahead(Object[] values) {
        if (enabled && values.length >= 2) offer(values);
    }

    @TruffleBoundary
    private static void offer(Object[] values) {
        Parallel p = NixContext.get(null).parallel;
        if (p != null) p.submit(values);
    }

    /**
     * The applications of {@code f} to each of the values, offered to workers like {@link #ahead},
     * for builtins that apply it to them all ({@code filter}, {@code concatMap}); null without
     * workers, and then the builtin calls {@code f} itself.
     */
    public static Object[] applications(Object f, Object[] values) {
        return enabled && values.length >= 2 ? offerApplications(f, values) : null;
    }

    @TruffleBoundary
    private static Object[] offerApplications(Object f, Object[] values) {
        Parallel p = NixContext.get(null).parallel;
        if (p == null) return null;
        Object[] apps = new Object[values.length];
        for (int i = 0; i < values.length; i++) apps[i] = Apply.lazy(f, values[i]);
        p.submit(apps);
        return apps;
    }

    private void submit(Object[] values) {
        if (!started) start();
        // The caller forces each value a level deeper (see CallDepth). Where it forces one at the
        // same level, a worker that reaches max-call-depth gives the value up, and the caller
        // evaluates it.
        batches.add(new Batch(values, EvalThread.current(null).callDepth + 1));
        if (idle.get() > 0) {
            synchronized (signal) {
                signal.notifyAll();
            }
        }
    }

    private synchronized void start() {
        if (started || stopped) return;
        started = true;
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
            Thunk thunk = null;
            int depth = 0;
            for (var it = batches.iterator(); it.hasNext(); ) {
                Batch b = it.next();
                thunk = b.claim();
                if (thunk != null) {
                    depth = b.callDepth;
                    break;
                }
                it.remove();
            }
            if (thunk == null) {
                idle.incrementAndGet();
                try {
                    synchronized (signal) {
                        if (batches.isEmpty()) signal.wait(100);
                    }
                } catch (InterruptedException e) {
                    return;
                } finally {
                    idle.decrementAndGet();
                }
                continue;
            }
            count("tasks");
            long t0 = STATS != null ? System.nanoTime() : 0;
            self.callDepth = depth;
            try {
                thunk.forceSlow();
                count("tasks done");
                add("tasks ms", STATS != null ? (System.nanoTime() - t0) / 1_000_000 : 0);
            } catch (Abandon e) {
                count("tasks given up");
            } catch (AbstractTruffleException | StackOverflowError e) {
                // An error: the thread that needs the value will find it.
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
