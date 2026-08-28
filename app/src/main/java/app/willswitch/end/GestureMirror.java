package app.willswitch.end;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.util.Log;

/**
 * Feeds the user's finger into the app while END has the touchscreen captured.
 *
 * v0.7 buffered the whole gesture, waited for ACTION_UP, and then dispatched a
 * single straight line from the down point to the up point with a clamped
 * duration. Every intermediate point and all velocity were lost, so an allowed
 * swipe arrived after the finger had already left the glass. That is what made
 * scrolling feel heavy.
 *
 * This class opens a continued stroke the moment the gesture is classified as
 * allowed and keeps appending real segments to it, so the content tracks the
 * finger roughly one dispatch round trip behind instead of one whole gesture
 * behind.
 */
final class GestureMirror {
    private static final String TAG = EndAccessibilityService.TAG;

    private static final long MIN_SEGMENT_MS = 12L;
    private static final long MAX_SEGMENT_MS = 110L;
    private static final long TAP_MS = 60L;

    interface Listener {
        /** Called once per mirrored gesture, when the first stroke goes out. */
        void onMirrorStarted();
        /** Called when the mirrored gesture has fully closed or failed. */
        void onMirrorFinished(boolean ok);
    }

    private final AccessibilityService service;
    private final Handler handler;
    private final Listener listener;

    private int maxX = Integer.MAX_VALUE;
    private int maxY = Integer.MAX_VALUE;

    private boolean open;
    private boolean awaitingCallback;
    private boolean fingerUp;
    private boolean failed;

    private GestureDescription.StrokeDescription stroke;
    private float lastX;
    private float lastY;
    private float pendingX;
    private float pendingY;
    private boolean hasPending;
    private long lastSegmentAt;

    GestureMirror(AccessibilityService service, Handler handler, Listener listener) {
        this.service = service;
        this.handler = handler;
        this.listener = listener;
    }

    void setBounds(int width, int height) {
        maxX = Math.max(1, width - 1);
        maxY = Math.max(1, height - 1);
    }

    boolean isOpen() {
        return open;
    }

    /**
     * Start mirroring. The first segment covers everything the finger has
     * already travelled since it went down, so nothing is dropped.
     */
    void begin(float x0, float y0, float x1, float y1, long travelledMs) {
        if (open) return;

        float sx = clampX(x0);
        float sy = clampY(y0);
        float ex = clampX(x1);
        float ey = clampY(y1);

        Path path = new Path();
        path.moveTo(sx, sy);
        if (sx == ex && sy == ey) {
            ey = clampY(ey + 1f);
        }
        path.lineTo(ex, ey);

        long duration = clampSegment(travelledMs);

        try {
            stroke = new GestureDescription.StrokeDescription(path, 0, duration, true);
        } catch (Exception e) {
            Log.w(TAG, "MIRROR begin rejected", e);
            fail();
            return;
        }

        open = true;
        fingerUp = false;
        failed = false;
        hasPending = false;
        lastX = ex;
        lastY = ey;
        lastSegmentAt = now();

        if (listener != null) listener.onMirrorStarted();
        dispatch(stroke);
    }

    /** Record a new finger position. Sent on the next free dispatch slot. */
    void move(float x, float y) {
        if (!open || fingerUp) return;
        pendingX = clampX(x);
        pendingY = clampY(y);
        hasPending = true;
        if (!awaitingCallback) flush();
    }

    /** The finger left the glass. Closes the stroke once the queue drains. */
    void finish() {
        if (!open) return;
        fingerUp = true;
        if (!awaitingCallback) flush();
    }

    /** Close the stroke immediately, for example when the wall is hit mid-swipe. */
    void abort() {
        if (!open) return;
        hasPending = false;
        fingerUp = true;
        if (!awaitingCallback) flush();
    }

    /** Single tap, used when the gesture never moved beyond the slop. */
    void tap(float x, float y) {
        Path path = new Path();
        path.moveTo(clampX(x), clampY(y));

        try {
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, TAP_MS))
                    .build();
            if (!service.dispatchGesture(gesture, null, handler)) {
                Log.w(TAG, "MIRROR tap refused");
            }
        } catch (Exception e) {
            Log.w(TAG, "MIRROR tap rejected", e);
        }
    }

    private void flush() {
        if (!open || awaitingCallback || failed) return;
        if (!hasPending && !fingerUp) return;

        float targetX = hasPending ? pendingX : lastX;
        float targetY = hasPending ? pendingY : lastY;
        hasPending = false;

        long elapsed = now() - lastSegmentAt;
        long duration = clampSegment(elapsed);

        Path path = new Path();
        path.moveTo(lastX, lastY);
        if (targetX == lastX && targetY == lastY) {
            // A zero length continuation is not a valid stroke. Nudge by one
            // pixel so the gesture can be closed cleanly.
            targetY = clampY(targetY + (targetY < maxY ? 1f : -1f));
        }
        path.lineTo(targetX, targetY);

        boolean willContinue = !fingerUp;

        try {
            stroke = stroke.continueStroke(path, 0, duration, willContinue);
        } catch (Exception e) {
            Log.w(TAG, "MIRROR continue rejected", e);
            fail();
            return;
        }

        lastX = targetX;
        lastY = targetY;
        lastSegmentAt = now();

        if (!willContinue) {
            GestureDescription gesture = build(stroke);
            open = false;
            if (gesture == null || !service.dispatchGesture(gesture, null, handler)) {
                fail();
                return;
            }
            if (listener != null) listener.onMirrorFinished(true);
            return;
        }

        dispatch(stroke);
    }

    private void dispatch(GestureDescription.StrokeDescription current) {
        GestureDescription gesture = build(current);
        if (gesture == null) {
            fail();
            return;
        }

        awaitingCallback = true;

        boolean accepted = service.dispatchGesture(
                gesture,
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription description) {
                        awaitingCallback = false;
                        flush();
                    }

                    @Override
                    public void onCancelled(GestureDescription description) {
                        awaitingCallback = false;
                        Log.w(TAG, "MIRROR stroke cancelled");
                        fail();
                    }
                },
                handler
        );

        if (!accepted) {
            awaitingCallback = false;
            Log.w(TAG, "MIRROR dispatch refused");
            fail();
        }
    }

    private GestureDescription build(GestureDescription.StrokeDescription current) {
        try {
            return new GestureDescription.Builder().addStroke(current).build();
        } catch (Exception e) {
            Log.w(TAG, "MIRROR build rejected", e);
            return null;
        }
    }

    private void fail() {
        boolean wasOpen = open;
        open = false;
        failed = true;
        hasPending = false;
        awaitingCallback = false;
        stroke = null;
        if (wasOpen && listener != null) listener.onMirrorFinished(false);
    }

    private static long clampSegment(long value) {
        if (value < MIN_SEGMENT_MS) return MIN_SEGMENT_MS;
        if (value > MAX_SEGMENT_MS) return MAX_SEGMENT_MS;
        return value;
    }

    private float clampX(float value) {
        if (value < 0f) return 0f;
        return value > maxX ? maxX : value;
    }

    private float clampY(float value) {
        if (value < 0f) return 0f;
        return value > maxY ? maxY : value;
    }

    private static long now() {
        return android.os.SystemClock.uptimeMillis();
    }
}
