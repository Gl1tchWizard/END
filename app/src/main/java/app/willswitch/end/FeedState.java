package app.willswitch.end;

import android.view.accessibility.AccessibilityEvent;

/**
 * In-memory state for one feed. A feed is an app, or a site inside a browser.
 *
 * v0.7 kept progress in SharedPreferences and counted it from four overlapping
 * heuristics (scroll delta, item index, content-change bursts and gesture
 * duration) that could all fire for the same swipe. This class keeps one
 * signal, TYPE_VIEW_SCROLLED, and caps how fast progress can accumulate.
 */
final class FeedState {
    private static final int NO_VALUE = Integer.MIN_VALUE;

    /** Nothing may add more than this many screens per {@link #WINDOW_MS}. */
    private static final float WINDOW_CAP = 1.5f;
    private static final long WINDOW_MS = 250L;

    /** Used when an app reports a scroll but no usable magnitude. */
    private static final float BLIND_STEP = 0.5f;
    private static final long BLIND_MIN_GAP_MS = 400L;

    final String key;
    final int profile;

    float progress;
    long lockedUntil;
    long lastActivityAt;

    /** Diagnostics: how many scroll events this feed has ever produced. */
    int scrollEvents;

    private int lastItemIndex = NO_VALUE;
    private int lastScrollY = NO_VALUE;
    private long lastBlindStepAt;
    private long windowStartAt;
    private float windowTotal;

    FeedState(String key, int profile) {
        this.key = key;
        this.profile = profile;
    }

    boolean isLocked(long now) {
        return lockedUntil > now;
    }

    void reset() {
        progress = 0f;
        lastItemIndex = NO_VALUE;
        lastScrollY = NO_VALUE;
        lastBlindStepAt = 0L;
        windowStartAt = 0L;
        windowTotal = 0f;
    }

    void clearLock() {
        lockedUntil = 0L;
        reset();
    }

    /**
     * How much of a screen this scroll event moved the feed forward.
     * Backward scrolling returns 0: END counts consumption, not movement.
     */
    float measure(AccessibilityEvent event, int screenHeight, long now) {
        scrollEvents++;

        float raw = rawContribution(event, screenHeight);
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

        // The app scrolled but told us nothing usable. Count a conservative
        // half screen, and never more than once per BLIND_MIN_GAP_MS so a
        // single fling cannot burn the whole limit.
        long now = event.getEventTime();
        if (now - lastBlindStepAt >= BLIND_MIN_GAP_MS) {
            lastBlindStepAt = now;
            return BLIND_STEP;
        }
        return 0f;
    }

    /**
     * Returns a positive value for forward item movement, a negative marker
     * for backward movement, and exactly 0 when the event carries no index.
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
