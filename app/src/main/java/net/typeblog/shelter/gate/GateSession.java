package net.typeblog.shelter.gate;

import android.os.SystemClock;

/**
 * In-memory unlock state of the outer gate.
 *
 * The state lives only in the process memory on purpose: if the process dies
 * (or the timeout below passes while the app is in the background) the user has
 * to go through the gate and enter the PIN again.
 */
public final class GateSession {
    // How long the session survives while the app is not in the foreground
    private static final long TIMEOUT_MS = 2 * 60 * 1000L;

    private static long sLastActive = 0;

    private GateSession() {
    }

    public static synchronized void unlock() {
        touch();
    }

    public static synchronized void touch() {
        sLastActive = SystemClock.elapsedRealtime();
    }

    public static synchronized void lock() {
        sLastActive = 0;
    }

    public static synchronized boolean isUnlocked() {
        return sLastActive != 0
                && SystemClock.elapsedRealtime() - sLastActive < TIMEOUT_MS;
    }
}
