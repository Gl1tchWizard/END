package app.willswitch.end;

import android.view.accessibility.AccessibilityEvent;

/**
 * In-memory state for one feed. A feed is an app, or a site inside a browser.
 *
 * Two jobs: count how much feed has been consumed before the limit, and
 * remember where the wall sits afterwards so the service can tell a scroll
 * back from a push past the end.
 */
final class FeedState {
    private static final int NO_VALUE = Integer.MIN_VALUE;

    /** Nothing may add more than this many screens per {@link #WINDOW_MS}. */
    private static final float WINDOW_CAP = 1.5f;
    private static final long WINDOW_MS = 250L;

    /** Used when an app reports a scroll but no usable magnitude. */
    private static final float BLIND_STEP = 0.5f;
    private static final long BLIND_MIN_GAP_MS = 400L;

    /**
     * Some apps, YouTube among them, never emit TYPE_VIEW_SCROLLED for their
     * feed. For those only, a dense burst of content changes counts as one
     * screen. It stays switched off for any feed that has ever produced a real
     * scroll event, so it cannot double count on Reddit or Instagram.
     */
    private static final int BURST_EVENTS = 5;
    private static final long BURST_SPAN_MS = 360L;
    private static final long BURST_MIN_GAP_MS = 900L;
    private static final long BURST_SETTLE_MS = 1500L;

    final String key;
    final int profile;

    float progress;
    long lockedUntil;
    long lastActivityAt;

    /** True once this feed has produced a real scroll event. */
    boolean sawScrollEvent;

    /** Feed position when the limit was reached. */
    private int wallIndex = NO_VALUE;
    private int wallScrollY = NO_VALUE;

    private int lastItemIndex = NO_VALUE;
    private int lastScrollY = NO_VALUE;
    private long lastBlindStepAt;
    private long windowStartAt;
    private float windowTotal;

    private long enteredAt;
    private long burstStartAt;
    private int burstCount;
    private long lastBurstCountAt;

    FeedState(String key, int profile) {
        this.key = key;
        this.profile = profile;
    }

    boolean isLocked(long now) {
        return lockedUntil > now;
    }

    void onEntered(long now) {
        enteredAt = now;
        burstStartAt = 0L;
        burstCount = 0;
    }

    void reset() {
        progress = 0f;
        lastItemIndex = NO_VALUE;
        lastScrollY = NO_VALUE;
        wallIndex = NO_VALUE;
        wallScrollY = NO_VALUE;
        lastBlindStepAt = 0L;
        windowStartAt = 0L;
        windowTotal = 0f;
        burstStartAt = 0L;
        burstCount = 0;
    }

    void clearLock() {
        lockedUntil = 0L;
        reset();
    }

    /** Pin the wall to wherever the feed stands right now. */
    void markWall() {
        wallIndex = lastItemIndex;
        wallScrollY = lastScrollY;
    }

    /**
     * True when this scroll event has taken the feed past the wall. Scrolling
     * back and moving around above the wall return false, so the feed behaves
     * like an ordinary list that has run out.
     */
    boolean pastWall(AccessibilityEvent event) {
        int from = event.getFromIndex();
        if (wallIndex != NO_VALUE && from >= 0) return from > wallIndex;

        int scrollY = event.getScrollY();
        if (wallScrollY != NO_VALUE && scrollY >= 0) {
            return scrollY > wallScrollY + 40;
        }

        // No usable position from this app: treat any forward movement as a
        // push past the end.
        int dy = event.getScrollDeltaY();
        if (dy > 0) return true;
        return dy == 0;
    }

    /**
     * How much of a screen this scroll event moved the feed forward.
     * Backward scrolling returns 0: END counts consumption, not movement.
     */
    float measureScroll(AccessibilityEvent event, int screenHeight, long now) {
        sawScrollEvent = true;
        return clampToWindow(rawContribution(event, screenHeight), now);
    }

    /** Fallback for feeds that never report scrolling. */
    float measureContentBurst(long now) {
        if (sawScrollEvent) return 0f;
        if (now - enteredAt < 1200L) return 0f;
        if (now - lastBurstCountAt < BURST_MIN_GAP_MS) return 0f;

        if (burstStartAt == 0L || now - burstStartAt > BURST_SPAN_MS) {
            burstStartAt = now;
            burstCount = 1;
            return 0f;
        }

        burstCount++;
        if (burstCount < BURST_EVENTS) return 0f;

        burstStartAt = 0L;
        burstCount = 0;
        lastBurstCountAt = now;
        return clampToWindow(1.0f, now);
    }

    /**
     * True when a content burst suggests the feed advanced while the wall is
     * up. Used only for feeds that never report scrolling.
     */
    boolean burstSuggestsAdvance(long now) {
        if (sawScrollEvent) return false;
        if (now - lastBurstCountAt < BURST_SETTLE_MS) return false;

        if (burstStartAt == 0L || now - burstStartAt > BURST_SPAN_MS) {
            burstStartAt = now;
            burstCount = 1;
            return false;
        }

        burstCount++;
        if (burstCount < BURST_EVENTS) return false;

        burstStartAt = 0L;
        burstCount = 0;
        lastBurstCountAt = now;
        return true;
    }

    private float clampToWindow(float raw, long now) {
        if (raw <= 0f) return 0f;

        if (now - windowStartAt > WINDOW_MS) {
            windowStartAt = now;
            windowTotal = 0f;
        }

        float room = WINDOW_CAP - windowTotal;
        if (room <= 0f) return 0f;

        float allowed = Math.min(raw, room);
        windowTotal += allowed;
        return allowed;
    }

    private float rawContribution(AccessibilityEvent event, int screenHeight) {
        if (profile == Feeds.PROFILE_ITEM) {
            float byItem = itemContribution(event);
            if (byItem != 0f) return Math.max(0f, byItem);
        }

        int dy = event.getScrollDeltaY();
        if (dy > 0) return Math.min(1.5f, (float) dy / (float) screenHeight);
        if (dy < 0 && dy != -1) return 0f;

        int scrollY = event.getScrollY();
        if (scrollY >= 0) {
            int previous = lastScrollY;
            lastScrollY = scrollY;
            if (previous != NO_VALUE) {
                int moved = scrollY - previous;
                if (moved > 0) {
                    return Math.min(1.5f, (float) moved / (float) screenHeight);
                }
                if (moved < 0) return 0f;
            }
        }

        if (profile != Feeds.PROFILE_ITEM) {
            float byItem = itemContribution(event);
            if (byItem != 0f) return Math.max(0f, byItem);
        }

        long now = event.getEventTime();
        if (now - lastBlindStepAt >= BLIND_MIN_GAP_MS) {
            lastBlindStepAt = now;
            return BLIND_STEP;
        }
        return 0f;
    }

    /**
     * Positive for forward item movement, negative for backward, exactly 0
     * when the event carries no index.
     */
    private float itemContribution(AccessibilityEvent event) {
        int from = event.getFromIndex();
        int to = event.getToIndex();
        if (from < 0 || to < from) return 0f;

        int previous = lastItemIndex;
        lastItemIndex = from;
        if (previous == NO_VALUE) return 0f;

        int moved = from - previous;
        if (moved == 0) return 0f;
        if (moved < 0) return -1f;

        if (profile == Feeds.PROFILE_ITEM) {
            return Math.min(3f, (float) moved);
        }
        int visible = Math.max(1, to - from + 1);
        return Math.min(1.5f, (float) moved / (float) visible);
    }
}
