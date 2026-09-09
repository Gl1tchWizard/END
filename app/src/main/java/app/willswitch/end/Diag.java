package app.willswitch.end;

import java.util.ArrayDeque;

/**
 * What the service is seeing right now, readable from the settings screen.
 *
 * Both run in the same process, so this is plain static state. It exists so a
 * person can tell whether END is counting without opening Logcat: open a feed,
 * scroll, come back to END, read the card.
 */
final class Diag {
    private Diag() {}

    static volatile boolean connected;
    static volatile boolean sensorAlive;
    static volatile long lastTouchAt;

    static volatile String foreground;
    static volatile String lastFeed;
    static volatile float lastProgress;
    static volatile long lastFeedEventAt;
    static volatile int scrollEvents;
    static volatile int burstCounts;
    static volatile boolean wallUp;
    static volatile String endMode = "down";

    private static final int LOG_LINES = 80;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    /** Same lines as Logcat, kept in memory so the app can show them. */
    static synchronized void log(String line) {
        long t = System.currentTimeMillis();
        long s = (t / 1000) % 60;
        long m = (t / 60_000) % 60;
        LOG.addLast(String.format("%02d:%02d %s", m, s, line));
        while (LOG.size() > LOG_LINES) LOG.removeFirst();
    }

    static synchronized String logText() {
        if (LOG.isEmpty()) return "nothing yet";
        StringBuilder out = new StringBuilder();
        for (String line : LOG) out.append(line).append('\n');
        return out.toString();
    }
}
