package com.bellamiecreative.keyboard;

/**
 * Decides on which Backspace auto-repeat ticks the recorded "hold" sound should play.
 *
 * The page keeps deleting every 70 ms (unchanged). The recorded iPhone hold sound repeats about every
 * 100 ms, so the sound is thinned out: a sound is due every {@link #INTERVAL_MS} on average, and it is only
 * ever played together with a real deletion tick, so audio and deletion stay in step.
 *
 * Pure Java (no Android classes) so the timing can be unit-simulated.
 */
final class BackspaceHoldPacer {
    static final long INTERVAL_MS = 100;   // average spacing of the recorded hold ticks (85-136 ms in the recording)
    static final long MIN_GAP_MS = 60;     // never closer than this, whatever the tick jitter
    static final long RESET_GAP_MS = 400;  // no tick for this long = a new hold sequence
    static final long TOLERANCE_MS = 8;    // timer jitter allowance when comparing with the due time

    private long due;
    private long lastTick = -1;
    private long lastPlay = -1;

    /** Call on every auto-repeat deletion tick. Returns true if the hold sound should play for this tick. */
    synchronized boolean onRepeatTick(long nowMs) {
        if (lastTick < 0 || nowMs - lastTick > RESET_GAP_MS) {
            due = nowMs;             // new hold sequence: the first repeat tick always sounds
            lastPlay = -1;
        }
        lastTick = nowMs;
        if (nowMs + TOLERANCE_MS < due) return false;
        if (lastPlay >= 0 && nowMs - lastPlay < MIN_GAP_MS) return false;
        lastPlay = nowMs;
        due += INTERVAL_MS;
        if (due < nowMs) due = nowMs + INTERVAL_MS;   // fell behind (busy main thread): do not burst to catch up
        return true;
    }

    /** A new press (single-tap sound) or release: forget the current sequence. */
    synchronized void reset() {
        lastTick = -1;
        lastPlay = -1;
    }
}
