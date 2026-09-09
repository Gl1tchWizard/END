package app.willswitch.end;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;

/**
 * In-memory state for one feed. A feed is an app, or a site inside a browser.
 *
 * Counts how much feed has been consumed. Two signals, never both for the
 * same movement:
 *
 * 1. TYPE_VIEW_SCROLLED, with an item-based or pixel-based profile. This is
 *    exact for Reddit, Instagram, X and web pages.
 * 2. A dense burst of content changes, for feeds that never report scrolling.
 *    YouTube Shorts is the main one. This signal is silenced for a while after
 *    every real scroll event, so it cannot double count on feeds that have both.
 *
 * Both signals only count when the user touched the screen just before, which
 * keeps autoplay, refresh and progress bars from filling the limit.
 */
final class FeedState {
    private static final int NO_VALUE = Integer.MIN_VALUE;

    /** Nothing may add more than this many screens per {@link #WINDOW_MS}. */
    private static final float WINDOW_CAP = 1.5f;
    private static final long WINDOW_MS = 250L;

    /** Used when an app reports a scroll but no usable magnitude. */
    private static final float BLIND_STEP = 0.5f;
    private static final long BLIND_MIN_GAP_MS = 400L;

    private static final int BURST_EVENTS = 6;
    private static final long BURST_SPAN_MS = 360L;
    private static final long BURST_MIN_GAP_MS = 900L;
    private static final long BURST_AFTER_SCROLL_MS = 1500L;
    private static final long BURST_AFTER_ENTER_MS = 1200L;

    final String key;
    final int profile;

    float progress;
    long lockedUntil;
    long lastActivityAt;

    /**
     * The scrollable that carries this feed, learned from scroll events.
     * Class name and item count are the cheap signature that comes with every
     * event; the view id is verified against the node itself only rarely,
     * because fetching a node is a round trip into the app's UI thread.
     */
    String feedViewId;
    String feedClassName;
    int feedItemCount = -1;
    Rect feedBounds;

    /**
     * Where the feed stands, in items for item feeds and in pixels for the
     * rest, and where the end was pinned. Only events from the feed's own
     * scrollable move it, so a different list in the same app cannot confuse
     * the end.
     */
    boolean positionKnown;
    boolean positionInPixels;
    boolean lastMoveForward;
    float position;
    float endPosition;

    /** True while the feed is at its end and the fence must hold. */
    boolean atEnd = true;

    private int lastItemIndex = NO_VALUE;
    private int lastScrollY = NO_VALUE;
    private long lastBlindStepAt;
    private long windowStartAt;
    private float windowTotal;

    private long enteredAt;
    private long lastScrollAt;
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
        atEnd = true;
        endPosition = 0f;
        positionKnown = false;
        positionInPixels = false;
        positionMode = POS_NONE;
        position = 0f;
        lastItemIndex = NO_VALUE;
        lastScrollY = NO_VALUE;
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

    /** Pin the end of the feed to wherever it stands right now. */
    void pinEnd() {
        endPosition = position;
        atEnd = true;
    }

    private static final int POS_NONE = 0;
    private static final int POS_DELTA = 1;
    private static final int POS_OFFSET = 2;
    private static final int POS_INDEX = 3;
    private int positionMode = POS_NONE;

    /**
     * Update the position from a scroll event. Returns false when the event
     * carries nothing usable.
     *
     * The first usable event fixes the unit for the life of the feed, and
     * exact pixel deltas are preferred: a RecyclerView's reported offset is an
     * estimate scaled by average item height, its delta is the real pixels.
     */
    boolean observePosition(AccessibilityEvent event) {
        int dy = event.getScrollDeltaY();
        int offset = event.getScrollY();
        int from = event.getFromIndex();
        boolean deltaValid = dy != 0 && dy != -1;

        if (positionMode == POS_NONE) {
            if (deltaValid) positionMode = POS_DELTA;
            else if (offset >= 0) positionMode = POS_OFFSET;
            else if (from >= 0) positionMode = POS_INDEX;
            else return false;
        }

        float before = position;
        switch (positionMode) {
            case POS_DELTA:
                if (!deltaValid) return false;
                position += dy;
                break;
            case POS_OFFSET:
                if (offset < 0) return false;
                position = offset;
                break;
            default:
                if (from < 0) return false;
                position = from;
                break;
        }

        positionInPixels = positionMode != POS_INDEX;
        positionKnown = true;
        lastMoveForward = position > before;
        return true;
    }

    /** Back in the app after leaving: assume the feed stands at its end. */
    void assumeAtEnd() {
        if (!positionKnown) return;
        position = endPosition;
        lastMoveForward = false;
    }

    /** How far past the end the feed stands, in the feed's own unit. */
    float overshoot() {
        return position - endPosition;
    }

    /** Screens consumed by this scroll event. Backward movement counts 0. */
    float measureScroll(AccessibilityEvent event, int screenHeight, long now) {
        lastScrollAt = now;
        return clampToWindow(rawContribution(event, screenHeight), now);
    }

    /** Fallback for feeds that never report scrolling. */
    float measureContentBurst(long now) {
        if (now - lastScrollAt < BURST_AFTER_SCROLL_MS) return 0f;
        if (now - enteredAt < BURST_AFTER_ENTER_MS) return 0f;
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
