package nixtruffle.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.Node;
import nixtruffle.NixLanguage;

/** What an evaluation keeps per thread (the main thread and {@link Parallel}'s workers). */
public final class EvalThread {
    /** CppNix's {@code callDepth} (see {@link CallDepth}). */
    public int callDepth;
    public final int maxCallDepth;
    /** A worker of {@link Parallel}: evaluates ahead of the main thread, without side effects. */
    public boolean worker;

    public EvalThread(int maxCallDepth) {
        this.maxCallDepth = maxCallDepth;
    }

    /**
     * Valid while no {@link Parallel} workers have been started (in any context): until then,
     * compiled code assumes it runs on the main thread, for which per-thread state costs nothing.
     */
    public static final Assumption SINGLE_THREADED = Truffle.getRuntime().createAssumption("no evaluation workers");

    /** The current thread's: the context's main thread's is at hand, the others' in a thread-local. */
    public static EvalThread current(Node location) {
        nixtruffle.NixContext ctx = nixtruffle.NixContext.get(location);
        if (SINGLE_THREADED.isValid() || Thread.currentThread() == ctx.mainThread) return ctx.main;
        return NixLanguage.get(location).evalThread();
    }
}
