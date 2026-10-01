package nixtruffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;
import nixtruffle.nodes.LazyCode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lazy update cell, modelled after thc's {@code Thunk}: code plus its captured environment, a
 * small state machine, and the memoized answer. An evaluation keeps millions of these, so they
 * have just two fields (16 bytes):
 *
 * <ul>
 *   <li>pending: {@code code} is the body to run, a {@link RootCallTarget} or the {@link LazyCode}
 *       of a source thunk (built when first run), and {@code data} the environment it closes over
 *       (or {@code [f, args...]} for builtin applications);
 *   <li>blackhole: being evaluated, and {@code code} is the thread evaluating it. The same thread
 *       re-entering it means the value depends on itself, which Nix reports as "infinite recursion
 *       encountered"; another thread ({@link Parallel}) waits for it;
 *   <li>done: {@code data} is the WHNF answer, and the code and environment are released (thc's
 *       "evaluated thunk releases its own references").
 * </ul>
 *
 * If evaluation throws, the thunk goes back to pending (like CppNix), so {@code builtins.tryEval}
 * and later forces re-run the computation and see the same error again.
 *
 * <p>The fast path lives in {@link nixtruffle.nodes.ForceNode}, which inline-caches the code and
 * calls it through a {@code DirectCallNode} so Graal can inline thunk bodies into force sites.
 * {@link #forceSlow} is for runtime code (builtins, printing, interop).
 */
public final class Thunk {
    private static final Object DONE = new Object();
    private static final VarHandle CODE;

    static {
        try {
            CODE = MethodHandles.lookup().findVarHandle(Thunk.class, "code", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The code to run (pending), the {@link Thread} running it (blackhole; an {@link Awaited} once
     * another thread waits for it), or {@link #DONE}.
     */
    private Object code;
    private Object data;

    public Thunk(Object code, Object env) {
        this.code = code;
        this.data = env;
    }

    /**
     * The code field. With {@link Parallel} workers, threads hand thunks to each other: a done
     * thunk is published with a release store, and read with an acquire load.
     */
    public Object getCode() {
        return EvalThread.SINGLE_THREADED.isValid() ? code : CODE.getAcquire(this);
    }

    public boolean isDone() { return getCode() == DONE; }
    /** Being evaluated, by this thread or another one. */
    public boolean isRunning() {
        Object c = getCode();
        return c instanceof Thread || c instanceof Awaited;
    }

    /** Running, with threads waiting for it: completing it wakes them. */
    private record Awaited(Thread owner) {}

    private static Thread owner(Object code) {
        return code instanceof Thread t ? t : code instanceof Awaited a ? a.owner() : null;
    }
    /** The answer, once done. */
    public Object getValue() { return data; }
    /** The environment, while pending. */
    public Object getEnv() { return data; }

    /** Whether {@code code} is something to run (not a thread running it, nor the done marker). */
    public static boolean isRunnable(Object code) {
        return code instanceof RootCallTarget || code instanceof LazyCode;
    }

    /** The call target of runnable {@code code}. */
    public static RootCallTarget target(Object code) {
        if (code instanceof LazyCode l) {
            RootCallTarget t = l.built();
            return t != null ? t : l.target();
        }
        return (RootCallTarget) code;
    }

    /**
     * Starts evaluating pending {@code pendingCode} on this thread; false if another thread got
     * there first (it is running or done then).
     */
    public boolean claim(Object pendingCode) {
        Thread me = Thread.currentThread();
        if (EvalThread.SINGLE_THREADED.isValid()) {
            code = me;
            return true;
        }
        return CODE.compareAndSet(this, pendingCode, me);
    }

    public void complete(Object result) {
        data = result;
        if (EvalThread.SINGLE_THREADED.isValid()) {
            code = DONE;
        } else if (CODE.getAndSet(this, DONE) instanceof Awaited) {
            wake();
        }
    }

    /** Evaluation failed: pending again, with its code. */
    public void reset(Object pendingCode) {
        if (EvalThread.SINGLE_THREADED.isValid()) {
            code = pendingCode;
        } else if (CODE.getAndSet(this, pendingCode) instanceof Awaited) {
            wake();
        }
    }

    /** Forces the thunk, whatever its state: claims it, waits for another thread, or finds a cycle. */
    @TruffleBoundary
    public Object forceSlow() {
        return force(null);
    }

    @TruffleBoundary
    public Object force(Node location) {
        while (true) {
            Object c = getCode();
            if (c == DONE) return data;
            Thread owner = owner(c);
            if (owner != null) {
                await(c, owner, location);
                continue;
            }
            Object env = data;
            if (!claim(c)) continue;
            boolean ok = false;
            try {
                Object result = target(c).call(env);
                complete(result);
                ok = true;
                return result;
            } finally {
                if (!ok) reset(c);
            }
        }
    }

    public static Object force(Object v) {
        return v instanceof Thunk t ? t.forceSlow() : v;
    }

    // ------------------------------------------------------- waiting

    /** For each thread that waits for a thunk, the thunk. */
    private static final ConcurrentHashMap<Thread, Thunk> WAITING = new ConcurrentHashMap<>();
    private static final Object WAKE = new Object();

    /**
     * Waits until {@code owner} is done with this thunk. A thread that finds its own thunk running
     * has an infinite recursion, and so do threads that each wait for a thunk the next one runs:
     * one thread evaluating them all would find a thunk it is running.
     */
    private void await(Object code, Thread owner, Node location) {
        Thread me = Thread.currentThread();
        if (owner == me) throw NixException.infiniteRecursion(location);
        // Have the owner wake us when it's done (only then: completing a thunk nobody waits for is cheap).
        if (code instanceof Thread && !CODE.compareAndSet(this, code, new Awaited(owner))) return;
        WAITING.put(me, this);
        long t0 = Parallel.STATS != null ? System.nanoTime() : 0;
        try {
            while (owner(getCode()) == owner) {
                Thread o = owner;
                for (int i = 0; i < 10_000; i++) {
                    Thunk w = WAITING.get(o);
                    Thread next = w == null ? null : owner(w.getCode());
                    if (next == null) break;
                    if (next == me) throw NixException.infiniteRecursion(location);
                    o = next;
                }
                synchronized (WAKE) {
                    if (owner(getCode()) == owner) WAKE.wait(20);
                }
                TruffleSafepoint.poll(location);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Parallel.Abandon();
        } finally {
            WAITING.remove(me);
            if (Parallel.STATS != null) {
                Parallel.add((EvalThread.current(location).worker ? "workers" : "main thread") + " waited ms", (System.nanoTime() - t0) / 1_000_000);
            }
        }
    }

    private static void wake() {
        synchronized (WAKE) {
            WAKE.notifyAll();
        }
    }
}
