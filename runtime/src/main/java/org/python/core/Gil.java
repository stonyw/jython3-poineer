package org.python.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The interpreter lock: the one thing that makes more than one Python thread at a time mean what
 * CPython means by it.
 *
 * <p>CPython serialises bytecode with the GIL, and the reference test suite is written against that
 * guarantee: a list append or a dictionary write is not interrupted half-way, so a test may share a
 * counter between threads without a lock of its own. This runtime has no bytecode-level lock of its
 * own, so one is laid over the whole dispatch loop: a frame takes it on entry and gives it back on
 * exit, and {@link Interpreter#interpret} lets it go every {@code SWITCH_TICKS} instructions. That
 * is coarser than CPython's five-millisecond switch, but it is the same promise — between two
 * instructions of one thread, another thread may run, never in the middle of one.
 *
 * <p>The lock is re-entrant because a Python frame calls Java which calls Python: every nested frame
 * takes it again and gives one hold back. It is only consulted once a second Python thread exists —
 * {@link #shared} latches on at the first {@code start_joinable_thread} — so a single-threaded run
 * pays one volatile read per frame and one per switch point, and nothing else.
 *
 * <p>Blocking has two shapes here, and both release every hold rather than one. {@link #suspend()}
 * gives back all holds and reports how many, so a thread that waits on a lock, a join or a sleep lets
 * another thread run; {@link #resume(int)} takes them again before the caller returns to Python. A
 * thread that is suspended is not counted, which is what keeps the count equal to the number of
 * holds this thread would have if it were running.
 */
final class Gil {

    /** The lock itself. Fairness is not promised to Python, which does not promise it either. */
    private static final ReentrantLock LOCK = new ReentrantLock();

    /** How many instructions one thread runs before the next one gets its turn. */
    private static final int SWITCH_TICKS = 64;

    /** How long one thread may run before another gets a turn; {@code sys.setswitchinterval}. */
    private static volatile long intervalNanos = 5_000_000L;

    /** When this thread last gave the lock up at a switch point, in {@link System#nanoTime()} terms. */
    private static final ThreadLocal<long[]> LAST_SWITCH = ThreadLocal.withInitial(() -> new long[1]);

    /** Whether any Python thread has ever been started; latched so the fast path stays single. */
    private static volatile boolean shared;

    /** How many Python threads are alive, the main one included; {@code _thread._count} asks this. */
    private static final AtomicInteger LIVE = new AtomicInteger(1);

    /** The Java thread that owns ident 1, captured when this class is first touched. */
    private static final Thread MAIN = Thread.currentThread();

    /** This thread's ident, assigned on first ask; 0 until then. */
    private static final ThreadLocal<long[]> IDENT = ThreadLocal.withInitial(() -> new long[1]);

    /** How many times this thread's frames hold the lock, so that a frame that did not lock lets go. */
    private static final ThreadLocal<int[]> HELD = ThreadLocal.withInitial(() -> new int[1]);

    private static final java.util.concurrent.atomic.AtomicLong NEXT_IDENT =
            new java.util.concurrent.atomic.AtomicLong(2);

    private Gil() {
    }

    /** {@code _thread.get_ident()}: 1 for the thread this runtime started on, a fresh number after. */
    static long ident() {
        if (Thread.currentThread() == MAIN) {
            return 1;
        }
        long[] slot = IDENT.get();
        if (slot[0] == 0) {
            slot[0] = NEXT_IDENT.getAndIncrement();
        }
        return slot[0];
    }

    /** The number a thread that is about to start will answer from {@link #ident()}. */
    static long newIdent() {
        return NEXT_IDENT.getAndIncrement();
    }

    /** Take the number chosen for this thread before it started. */
    static void adoptIdent(long chosen) {
        IDENT.get()[0] = chosen;
    }

    /** {@code _thread._get_main_thread_ident()}: the number {@link #ident()} answers on the first thread. */
    static long mainIdent() {
        return 1;
    }

    /** A Python thread has started: the lock is now consulted, and `_count` includes it. */
    static void threadStarted() {
        shared = true;
        LIVE.incrementAndGet();
    }

    /** A Python thread is finishing: `_count` no longer includes it. */
    static void threadFinished() {
        LIVE.decrementAndGet();
    }

    /** `_thread._count()`: Python threads alive other than the main one. */
    static int live() {
        return Math.max(0, LIVE.get() - 1);
    }

    /** Whether more than one thread has ever run, which is the only time the lock does anything. */
    static boolean shared() {
        return shared;
    }

    /** Take the lock for a frame. Nothing is taken while this is the only Python thread. */
    static void enter() {
        if (!shared) {
            return;
        }
        LOCK.lock();
        HELD.get()[0]++;
    }

    /**
     * Take the lock on behalf of the frame that is already running here, because a thread was just
     * started from it and the frame was entered before there was anything to share.
     *
     * <p>The frame's own exit gives the hold back; the call is a no-op when this thread already holds
     * the lock, so a frame that starts several threads still holds it once.
     */
    static void ensureHeld() {
        if (!shared || LOCK.getHoldCount() > 0) {
            return;
        }
        LOCK.lock();
        HELD.get()[0]++;
    }

    /** Give back the hold {@link #enter()} took, if this frame took one. */
    static void exit() {
        int[] held = HELD.get();
        if (held[0] == 0) {
            return;
        }
        held[0]--;
        LOCK.unlock();
    }

    /**
     * Let every other Python thread run for a moment, keeping this thread's place in the lock.
     *
     * <p>The dispatch loop calls this between instructions. Releasing hold by hold and taking them
     * again means a nested call keeps its own holds, so the thread returns to exactly the state it
     * left: this is a switch point, not a suspension.
     */
    static void switchThreads() {
        // Two reasons to give the lock up: the interval has run out, or another thread is waiting for
        // it. The second is what keeps a tight loop that never blocks from starving a starter that is
        // waiting to run — the reference's own interval would let the spinner keep the GIL for its
        // whole slice, and here a queued thread means the slice is over.
        if (!shared || (!dueForSwitch() && !LOCK.hasQueuedThreads())) {
            return;
        }
        int holds = LOCK.getHoldCount();
        for (int i = 0; i < holds; i++) {
            LOCK.unlock();
        }
        for (int i = 0; i < holds; i++) {
            LOCK.lock();
        }
    }

    /**
     * Whether this thread has held the lock for longer than the switch interval.
     *
     * <p>The reference measures the same thing in the wall clock and the same default, five
     * milliseconds; here the dispatch loop asks between instructions, so a fast thread yields just as
     * often as a slow one rather than a fixed number of instructions apart.
     */
    private static boolean dueForSwitch() {
        long now = System.nanoTime();
        long[] last = LAST_SWITCH.get();
        if (now - last[0] < intervalNanos) {
            return false;
        }
        last[0] = now;
        return true;
    }

    /** {@code sys.getswitchinterval()}: seconds one thread may run before another gets a turn. */
    static double switchInterval() {
        return intervalNanos / 1e9;
    }

    /**
     * {@code sys.setswitchinterval(interval)}.
     *
     * <p>A value that is not strictly positive is refused with the reference's sentence; a very large
     * one is clamped to what a nanosecond count can hold rather than wrapping negative.
     */
    static void setSwitchInterval(double seconds) {
        if (!(seconds > 0)) {
            throw Slots.valueError("switch interval must be strictly positive");
        }
        double nanos = seconds * 1e9;
        intervalNanos = nanos >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1, (long) nanos);
    }

    /** How many instructions one thread runs before {@link #switchThreads()} is called. */
    static int switchTicks() {
        return SWITCH_TICKS;
    }

    /**
     * Give back every hold this thread has, returning how many, so that a blocking wait can be
     * entered without stopping the rest of the program.
     *
     * @return the number of holds, to be given back to {@link #resume(int)}
     */
    static int suspend() {
        int holds = LOCK.getHoldCount();
        for (int i = 0; i < holds; i++) {
            LOCK.unlock();
        }
        return holds;
    }

    /** Take back the holds {@link #suspend()} gave up, in the same number. */
    static void resume(int holds) {
        for (int i = 0; i < holds; i++) {
            LOCK.lock();
        }
    }
}
