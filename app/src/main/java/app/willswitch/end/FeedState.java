package app.willswitch.end;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * In-memory state for one feed. A feed is an app, or a site inside a browser.
 *
 * Built on what feeds were measured to report (docs/measurements.md), not on
 * what they were assumed to report:
 *
 * - A RecyclerView, which is the YouTube home list and the Shorts pager alike,
 *   reports exact pixel deltas and always a scroll offset of 0. The offset is
 *   worthless there and the delta is everything.
 * - A web page reports no delta at all and an exact absolute offset.
 * - One app holds several lists, and a browser reports the same movement from
 *   two nodes at once.
 *
 * So a feed tracks lists, one at a time, each in pixels and each with its own
 * way of reporting. Progress is the furthest travel in screens of the list's
 * own height, which makes one Short exactly one screen. Reading back costs
 * nothing, and the end is pinned at the exact pixel where the limit fell.
 *
 * Plain Java on purpose: no Android types, so it runs in a unit test.
 */
final class FeedState {

    /** Result bits of {@link #observe}. */
    static final int MOVED = 1;
    static final int LIMIT_REACHED = 2;

    private static final int MODE_NONE = 0;
    private static final int MODE_DELTA = 1;
    private static final int MODE_OFFSET = 2;

    private static final int MAX_LISTS = 6;
    private static final float LIMIT_TOLERANCE = 0.02f;

    /**
     * After a list comes on screen, its first movements are the app's own: a
     * list that is shown again puts itself back where it was with one large
     * scroll. That is not the user and not an overscroll.
     */
    private static final long ARRIVAL_MS = 250L;

    private static final int BURST_EVENTS = 6;
    private static final long BURST_SPAN_MS = 360L;
    private static final long BURST_MIN_GAP_MS = 900L;
    private static final long BURST_AFTER_ENTER_MS = 1200L;

    /** One vertical list inside the feed: a home list, a pager, a web page. */
    static final class ListTrack {
        final int id;
        final String className;
        String viewId;
        boolean scrollable;
        int left, top, right, bottom;

        /** One screen of this list, fixed when it is first seen. */
        int height;

        /**
         * The lowest the list's top edge has been seen. A list whose top edge
         * now lies above that has an app bar scrolled out of view above it.
         */
        int restTop;

        private int mode = MODE_NONE;
        float position;
        float countedTo;
        boolean endPinned;
        float endPosition;
        boolean lastMoveForward;
        long lastEventAt;
        private boolean arriving;
        private long arrivedAt;

        ListTrack(int id, String className) {
            this.id = id;
            this.className = className;
        }

        boolean positionKnown() {
            return mode != MODE_NONE;
        }

        String unit() {
            return mode == MODE_DELTA ? "deltas" : mode == MODE_OFFSET ? "offset" : "nothing yet";
        }
    }

    final String key;

    float progress;
    long lockedUntil;
    long lastActivityAt;

    /** The list being scrolled now, or null before any list has reported. */
    ListTrack list;

    /** True once any list of this feed has reported vertical movement. */
    boolean scrollSeen;

    private final Map<Integer, ListTrack> lists = new LinkedHashMap<>();
    private final Set<String> shadows = new HashSet<>();

    private long enteredAt;
    private long burstStartAt;
    private int burstCount;
    private long lastBurstCountAt;

    FeedState(String key) {
        this.key = key;
    }

    boolean isLocked(long now) {
        return lockedUntil > now;
    }

    void onEntered(long now) {
        enteredAt = now;
        burstStartAt = 0L;
        burstCount = 0;
    }

    /** Progress starts over. Every list counts on from where it stands. */
    void reset() {
        progress = 0f;
        burstStartAt = 0L;
        burstCount = 0;
        for (ListTrack l : lists.values()) {
            l.endPinned = false;
            l.countedTo = l.position;
        }
    }

    void clearLock() {
        lockedUntil = 0L;
        reset();
    }

    /**
     * The end has been reached by a signal other than this list's own travel.
     * Pin the end of the current list where it stands; every other list gets
     * its end when it is next seen.
     */
    void pinEndHere() {
        for (ListTrack l : lists.values()) l.endPinned = false;
        if (list != null && list.positionKnown()) {
            list.endPosition = list.position;
            list.endPinned = true;
        }
    }

    // ------------------------------------------------------------------ lists

    /**
     * A class whose scroll events repeat what another node already reports.
     * Brave sends every movement twice: from the page, with an offset, and
     * from a frame around it, with deltas. Counting both doubles the speed,
     * and letting both move one position tears it apart.
     */
    boolean isShadow(String className) {
        return className != null && shadows.contains(className);
    }

    void shadow(String className) {
        if (className != null) shadows.add(className);
    }

    void unshadow(String className) {
        shadows.remove(className);
    }

    /**
     * Make this node the list being tracked, remembering what is known of it.
     *
     * @param arrived the list has just come on screen, as opposed to being
     *                looked at again at the start of another swipe
     */
    ListTrack useList(int id, String className, String viewId, boolean scrollable,
                      int left, int top, int right, int bottom, boolean arrived) {
        ListTrack l = lists.remove(id);
        if (l == null || !same(l.className, className)) {
            l = new ListTrack(id, className);
            l.height = Math.max(1, bottom - top);
        }
        lists.put(id, l);   // most recently used last
        if (lists.size() > MAX_LISTS) {
            Iterator<Integer> oldest = lists.keySet().iterator();
            oldest.next();
            oldest.remove();
        }

        l.viewId = viewId;
        l.scrollable = scrollable;
        l.left = left;
        l.top = top;
        l.right = right;
        l.bottom = bottom;
        l.restTop = Math.max(l.restTop, top);
        l.arriving = arrived;
        l.arrivedAt = 0L;
        list = l;
        return l;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // --------------------------------------------------------------- movement

    /**
     * One scroll event from the current list.
     *
     * @param dy       AccessibilityEvent.getScrollDeltaY()
     * @param dx       AccessibilityEvent.getScrollDeltaX()
     * @param scrollY  AccessibilityEvent.getScrollY()
     * @param touched  the screen was touched just before, so this is the user
     * @param locked   the feed has ended; nothing is counted
     * @param limit    screens allowed
     * @return MOVED and LIMIT_REACHED bits
     */
    int observe(int dy, int dx, int scrollY, boolean touched, boolean locked, float limit, long now) {
        ListTrack l = list;
        if (l == null) return 0;

        // A view that never set its deltas reports -1 for both. A real move of
        // one pixel upward has dx 0.
        boolean deltaSet = !(dy == -1 && dx == -1);

        if (l.mode == MODE_NONE) {
            if (deltaSet && dy != 0) {
                l.mode = MODE_DELTA;
                l.position = 0f;
                l.countedTo = 0f;
            } else if ((!deltaSet && scrollY >= 0) || (deltaSet && dy == 0 && dx == 0 && scrollY > 0)) {
                l.mode = MODE_OFFSET;
                l.position = scrollY;
                l.countedTo = scrollY;
            } else {
                return 0;
            }
        }

        float before = l.position;

        if (l.mode == MODE_DELTA) {
            if (!deltaSet || dy == 0) return 0;
            l.position += dy;
        } else {
            if (scrollY < 0) return 0;
            if (before - scrollY > 2f * l.height && scrollY < l.height / 4f) {
                // Several screens up in a single step, to the top: a new page
                // in the same tab, not a scroll. It starts from here.
                before = scrollY;
                l.countedTo = scrollY;
                l.endPinned = false;
            }
            l.position = scrollY;
        }

        l.lastEventAt = now;
        scrollSeen = true;

        if (l.arriving) {
            if (l.arrivedAt == 0L) l.arrivedAt = now;
            if (now - l.arrivedAt <= ARRIVAL_MS) {
                // The list settling into place. Everything moves along with it.
                float moved = l.position - before;
                l.countedTo += moved;
                if (l.endPinned) l.endPosition += moved;
                return 0;
            }
            l.arriving = false;
        }

        if (locked && !l.endPinned) {
            // First sight of this list since the feed ended: its end is where
            // it stood before this movement.
            l.endPosition = before;
            l.endPinned = true;
        }

        if (l.position == before) return 0;
        l.lastMoveForward = l.position > before;

        int result = MOVED;

        if (!locked && l.position > l.countedTo) {
            if (touched) {
                progress += (l.position - l.countedTo) / l.height;
                lastActivityAt = now;
                // A pager's pages do not always add up to the last pixel, and
                // floats do not add up to 1. Two percent of a screen short of
                // the limit is the limit.
                if (progress >= limit - LIMIT_TOLERANCE) {
                    float excess = (progress - limit) * l.height;
                    for (ListTrack other : lists.values()) other.endPinned = false;
                    l.endPosition = l.position - Math.max(0f, excess);
                    l.endPinned = true;
                    progress = limit;
                    result |= LIMIT_REACHED;
                }
            }
            // Movement that was not the user's, autoplay or a jump, is not
            // counted now and not later either.
            l.countedTo = l.position;
        }
        return result;
    }

    /** How far past its end the current list stands, in pixels. Never negative. */
    float overshoot() {
        return Math.max(0f, signedOvershoot());
    }

    /** The same, but negative while the list stands above its end. */
    float signedOvershoot() {
        ListTrack l = list;
        if (l == null || !l.endPinned) return 0f;
        return l.position - l.endPosition;
    }

    // ---------------------------------------------------------------- fallback

    /**
     * For feeds that never report scrolling: a dense burst of content changes
     * is one screen. Silent for good once any list of this feed has reported
     * real movement, so the two signals can never count the same swipe.
     */
    float measureContentBurst(long now) {
        if (scrollSeen) return 0f;
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
        return 1.0f;
    }
}
