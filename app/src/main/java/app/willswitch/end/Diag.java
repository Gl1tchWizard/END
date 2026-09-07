package app.willswitch.end;

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
}
