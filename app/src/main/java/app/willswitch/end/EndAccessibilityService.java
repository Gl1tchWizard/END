package app.willswitch.end;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

public class EndAccessibilityService extends AccessibilityService {
    private static final String TAG = "ENDDBG";
    private static final int TEXT = Color.rgb(245, 245, 245);
    private static final int ACID = Color.rgb(216, 255, 0);

    private WindowManager windowManager;
    private View badge;
    private TextView countdown;
    private TextView badgeState;
    private String badgePackage;
    private String foregroundPackage;

    private int screenHeight;
    private int screenWidth;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // Progress tracking before END.
    private final Map<String, Integer> lastFromIndex = new HashMap<>();
    private final Map<String, Long> lastIndexEventAt = new HashMap<>();
    private final Map<String, Integer> lastScrollY = new HashMap<>();
    private final Map<String, Long> lastFallbackBurstAt = new HashMap<>();

    private final Map<String, Long> enteredAt = new HashMap<>();
    private final Map<String, Long> touchStartAt = new HashMap<>();
    private final Map<String, Integer> contentEventsDuringTouch = new HashMap<>();
    private final Map<String, Long> lastTouchSeenAt = new HashMap<>();
    private final Map<String, Long> contentBurstStartAt = new HashMap<>();
    private final Map<String, Integer> contentBurstCount = new HashMap<>();
    private final Map<String, Long> lastContentFallbackCountAt = new HashMap<>();
    private final Map<String, Long> lastReliableScrollAt = new HashMap<>();

    // Android 14+ finite-feed input guard.
    // Once a feed is ended, SOURCE_TOUCHSCREEN is captured by this service.
    // Forward swipes are discarded. Allowed gestures are replayed with dispatchGesture().
    private boolean motionGuardEnabled = false;
    private boolean replayInProgress = false;

    private float gestureDownX;
    private float gestureDownY;
    private float gestureLastX;
    private float gestureLastY;
    private long gestureDownAt;
    private int gestureMaxPointers = 1;
    private String gesturePackage;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (badgePackage == null) return;

            long until = EndPrefs.lockedUntil(EndAccessibilityService.this, badgePackage);
            long remaining = until - System.currentTimeMillis();

            if (remaining <= 0) {
                String finished = badgePackage;
                EndPrefs.clearSession(EndAccessibilityService.this, finished);
                hideBadge();
                setMotionGuard(false);
                return;
            }

            if (countdown != null) countdown.setText(formatRemaining(remaining));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        screenHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        screenWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        setMotionGuard(false);
        Log.i(TAG, "SERVICE CONNECTED screen=" + screenWidth + "x" + screenHeight);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;

        String pkg = event.getPackageName().toString();
        if (pkg.equals(getPackageName())) return;

        final int type = event.getEventType();
        final long when = event.getEventTime() > 0
                ? event.getEventTime()
                : System.currentTimeMillis();
        final boolean protectedApp = EndPrefs.isProtected(this, pkg);

        if (protectedApp) {
            EndPrefs.recordDebugEvent(this, pkg, eventTypeName(type));
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Don't treat transient System UI as leaving the app.
            if (!"com.android.systemui".equals(pkg)) {
                boolean changedApp = foregroundPackage == null || !foregroundPackage.equals(pkg);
                foregroundPackage = pkg;

                if (protectedApp && changedApp) enteredAt.put(pkg, when);

                if (protectedApp && isLocked(pkg)) {
                    showBadge(pkg);
                    setMotionGuard(true);
                } else {
                    if (badgePackage != null && !badgePackage.equals(pkg)) hideBadge();
                    setMotionGuard(false);
                }
            }
            return;
        }

        if (!protectedApp) return;
        foregroundPackage = pkg;

        if (isLocked(pkg)) {
            showBadge(pkg);

            if (Build.VERSION.SDK_INT >= 34) {
                // Exact behaviour on Android 14+: intercept only after END.
                setMotionGuard(true);
            } else {
                // Older Android fallback cannot selectively filter raw touch.
                // If forward movement leaks through, push the feed back one screen.
                legacyBounceBackIfNeeded(event, pkg, when);
            }
            return;
        }

        // We're not ended anymore.
        if (badgePackage != null && badgePackage.equals(pkg)) hideBadge();
        setMotionGuard(false);

        resetPartialProgressAfterLongBreak(pkg);

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastReliableScrollAt.put(pkg, when);
            float contribution = scrollContribution(event, pkg);
            if (contribution > 0f) {
                applyContribution(pkg, contribution, "VIEW_SCROLLED");
            }
            return;
        }

        // Pair content rebuilds with touch interaction when Android provides it.
        if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            touchStartAt.put(pkg, when);
            contentEventsDuringTouch.put(pkg, 0);
            lastTouchSeenAt.put(pkg, when);
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            Long touchStart = touchStartAt.get(pkg);
            if (touchStart != null && when - touchStart <= 3000L) {
                contentEventsDuringTouch.put(
                        pkg,
                        contentEventsDuringTouch.getOrDefault(pkg, 0) + 1
                );
            }

            handleContentBurstFallback(pkg, when);
            return;
        }

        if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
            lastTouchSeenAt.put(pkg, when);
            Long started = touchStartAt.remove(pkg);
            int changes = contentEventsDuringTouch.getOrDefault(pkg, 0);
            contentEventsDuringTouch.remove(pkg);

            if (started != null) {
                long duration = when - started;
                if (duration >= 110L && duration <= 2500L && changes >= 3) {
                    applyContribution(
                            pkg,
                            1.0f,
                            "GESTURE duration=" + duration + "ms changes=" + changes
                    );
                }
            }
        }
    }

    /**
     * Android 14+ gives AccessibilityService a generic MotionEvent input path.
     * Once END is reached, those touchscreen events are captured here instead of
     * reaching the app. We selectively replay everything except a forward
     * vertical swipe.
     *
     * Finger UP   (dy < 0) = content moves forward/down = BLOCK.
     * Finger DOWN (dy > 0) = content moves back/up       = ALLOW.
     */
    @Override
    public void onMotionEvent(MotionEvent event) {
        if (Build.VERSION.SDK_INT < 34 || event == null) return;
        if (!motionGuardEnabled || replayInProgress) return;

        String pkg = foregroundPackage;
        if (pkg == null || !EndPrefs.isProtected(this, pkg) || !isLocked(pkg)) {
            setMotionGuard(false);
            return;
        }

        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            gestureDownX = event.getX();
            gestureDownY = event.getY();
            gestureLastX = gestureDownX;
            gestureLastY = gestureDownY;
            gestureDownAt = event.getEventTime();
            gestureMaxPointers = Math.max(1, event.getPointerCount());
            gesturePackage = pkg;
            return;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            gestureMaxPointers = Math.max(gestureMaxPointers, event.getPointerCount());
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            gestureLastX = event.getX();
            gestureLastY = event.getY();
            gestureMaxPointers = Math.max(gestureMaxPointers, event.getPointerCount());
            return;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            resetCapturedGesture();
            return;
        }

        if (action != MotionEvent.ACTION_UP) return;

        gestureLastX = event.getX();
        gestureLastY = event.getY();

        final String capturedPkg = gesturePackage;
        final float x0 = gestureDownX;
        final float y0 = gestureDownY;
        final float x1 = gestureLastX;
        final float y1 = gestureLastY;
        final long duration = Math.max(1L, event.getEventTime() - gestureDownAt);
        final int pointers = gestureMaxPointers;

        resetCapturedGesture();

        if (capturedPkg == null
                || !capturedPkg.equals(foregroundPackage)
                || !isLocked(capturedPkg)) {
            replayGesture(x0, y0, x1, y1, duration);
            return;
        }

        float dx = x1 - x0;
        float dy = y1 - y0;
        float ax = Math.abs(dx);
        float ay = Math.abs(dy);

        float tapSlop = dp(20);
        float verticalThreshold = dp(42);

        boolean startsInSystemEdge =
                y0 >= screenHeight * 0.90f
                        || x0 <= dp(22)
                        || x0 >= screenWidth - dp(22);

        // Multi-touch is rare in a feed. Preserve a first-pointer approximation
        // rather than making the app feel dead.
        if (pointers > 1) {
            replayGesture(x0, y0, x1, y1, duration);
            return;
        }

        // Preserve Android's edge/home gestures and app bottom navigation.
        if (startsInSystemEdge) {
            replayGesture(x0, y0, x1, y1, duration);
            return;
        }

        // Normal taps remain normal.
        if (ax <= tapSlop && ay <= tapSlop) {
            replayTap(x1, y1);
            return;
        }

        // Horizontal carousels / UI gestures remain usable.
        if (ax > ay * 1.15f) {
            replayGesture(x0, y0, x1, y1, duration);
            return;
        }

        if (dy <= -verticalThreshold) {
            // This is the only gesture END removes:
            // finger swipes upward -> asks for fresh content below.
            Log.i(TAG, "FINITE WALL blocked forward swipe pkg=" + capturedPkg
                    + " dy=" + dy);
            pulseBadge();
            return;
        }

        // Downward/back scrolls and ambiguous small vertical gestures are replayed.
        replayGesture(x0, y0, x1, y1, duration);
    }

    private void replayTap(float x, float y) {
        if (Build.VERSION.SDK_INT < 24) return;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70))
                .build();

        dispatchAllowedGesture(gesture);
    }

    private void replayGesture(float x0, float y0, float x1, float y1, long originalDuration) {
        if (Build.VERSION.SDK_INT < 24) return;

        Path path = new Path();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);

        long duration = Math.max(90L, Math.min(550L, originalDuration));

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                .build();

        dispatchAllowedGesture(gesture);
    }

    private void dispatchAllowedGesture(GestureDescription gesture) {
        if (Build.VERSION.SDK_INT < 24) return;

        // MotionEvent sources captured by this service are not sent to the app.
        // Temporarily release that capture, inject the allowed gesture, then
        // restore the finite-feed guard when dispatch completes.
        replayInProgress = true;
        setMotionGuard(false);

        boolean accepted = dispatchGesture(
                gesture,
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        replayInProgress = false;
                        handler.postDelayed(() -> {
                            if (shouldGuardForeground()) setMotionGuard(true);
                        }, 45L);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        replayInProgress = false;
                        handler.postDelayed(() -> {
                            if (shouldGuardForeground()) setMotionGuard(true);
                        }, 45L);
                    }
                },
                handler
        );

        if (!accepted) {
            replayInProgress = false;
            handler.postDelayed(() -> {
                if (shouldGuardForeground()) setMotionGuard(true);
            }, 45L);
        }
    }

    private boolean shouldGuardForeground() {
        return Build.VERSION.SDK_INT >= 34
                && foregroundPackage != null
                && EndPrefs.isProtected(this, foregroundPackage)
                && isLocked(foregroundPackage);
    }

    private void setMotionGuard(boolean enabled) {
        if (Build.VERSION.SDK_INT < 34) return;
        if (motionGuardEnabled == enabled) return;

        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;

        info.setMotionEventSources(enabled ? InputDevice.SOURCE_TOUCHSCREEN : 0);
        setServiceInfo(info);
        motionGuardEnabled = enabled;

        Log.i(TAG, "MOTION GUARD " + (enabled ? "ON" : "OFF")
                + " pkg=" + foregroundPackage);
    }

    private void resetCapturedGesture() {
        gestureDownX = gestureDownY = gestureLastX = gestureLastY = 0f;
        gestureDownAt = 0L;
        gestureMaxPointers = 1;
        gesturePackage = null;
    }

    private void handleContentBurstFallback(String pkg, long when) {
        Long lastScroll = lastReliableScrollAt.get(pkg);
        if (lastScroll != null && when - lastScroll < 3000L) return;

        Long lastTouch = lastTouchSeenAt.get(pkg);
        if (lastTouch != null && when - lastTouch < 4500L) return;

        Long entered = enteredAt.get(pkg);
        if (entered != null && when - entered < 1200L) return;

        Long start = contentBurstStartAt.get(pkg);
        int count;

        if (start == null || when - start > 360L) {
            contentBurstStartAt.put(pkg, when);
            contentBurstCount.put(pkg, 1);
            return;
        } else {
            count = contentBurstCount.getOrDefault(pkg, 0) + 1;
            contentBurstCount.put(pkg, count);
        }

        if (count >= 5) {
            Long lastCount = lastContentFallbackCountAt.get(pkg);
            if (lastCount == null || when - lastCount >= 1100L) {
                lastContentFallbackCountAt.put(pkg, when);
                contentBurstStartAt.remove(pkg);
                contentBurstCount.remove(pkg);
                applyContribution(pkg, 1.0f, "CONTENT_BURST");
            }
        }
    }

    private void applyContribution(String pkg, float contribution, String reason) {
        if (contribution <= 0f || isLocked(pkg)) return;

        float before = EndPrefs.progress(this, pkg);
        float next = before + contribution;

        Log.i(TAG, "PROGRESS pkg=" + pkg
                + " reason=" + reason
                + " before=" + before
                + " +" + contribution
                + " => " + next
                + " / limit=" + EndPrefs.screens(this));

        EndPrefs.setProgress(this, pkg, next);
        EndPrefs.setLastActivity(this, pkg, System.currentTimeMillis());

        if (next >= EndPrefs.screens(this)) lock(pkg);
    }

    private float scrollContribution(AccessibilityEvent event, String pkg) {
        int dy = event.getScrollDeltaY();

        if (dy > 0) {
            return Math.min(1.25f, (float) dy / (float) screenHeight);
        }

        if (dy < 0) {
            return 0f;
        }

        int scrollY = event.getScrollY();

        if (scrollY >= 0) {
            Integer previousY = lastScrollY.put(pkg, scrollY);

            if (previousY != null) {
                int movedY = scrollY - previousY;

                if (movedY > 0) {
                    return Math.min(1.25f, (float) movedY / (float) screenHeight);
                }

                if (movedY < 0) return 0f;
            }
        }

        int from = event.getFromIndex();
        int to = event.getToIndex();

        if (from >= 0 && to >= from) {
            Integer previous = lastFromIndex.put(pkg, from);
            long now = event.getEventTime();
            Long previousAt = lastIndexEventAt.put(pkg, now);

            if (previous != null && previousAt != null && now - previousAt >= 90) {
                int moved = from - previous;

                if (moved > 0) {
                    int visible = Math.max(1, to - from + 1);
                    return Math.min(1.0f, (float) moved / (float) visible);
                }

                if (moved < 0) return 0f;
            }
        }

        long now = event.getEventTime();
        Long lastBurst = lastFallbackBurstAt.get(pkg);

        if (lastBurst == null || now - lastBurst >= 650) {
            lastFallbackBurstAt.put(pkg, now);
            return 1.0f;
        }

        return 0f;
    }

    private void resetPartialProgressAfterLongBreak(String pkg) {
        long last = EndPrefs.lastActivity(this, pkg);
        if (last <= 0) return;

        long cooldown = EndPrefs.cooldownMinutes(this) * 60_000L;

        if (System.currentTimeMillis() - last >= cooldown) {
            EndPrefs.setProgress(this, pkg, 0f);

            lastFromIndex.remove(pkg);
            lastIndexEventAt.remove(pkg);
            lastScrollY.remove(pkg);
            lastFallbackBurstAt.remove(pkg);
            enteredAt.remove(pkg);
            touchStartAt.remove(pkg);
            contentEventsDuringTouch.remove(pkg);
            lastTouchSeenAt.remove(pkg);
            contentBurstStartAt.remove(pkg);
            contentBurstCount.remove(pkg);
            lastContentFallbackCountAt.remove(pkg);
            lastReliableScrollAt.remove(pkg);
        }
    }

    private boolean isLocked(String pkg) {
        long until = EndPrefs.lockedUntil(this, pkg);

        if (until <= 0) return false;

        if (until <= System.currentTimeMillis()) {
            EndPrefs.clearSession(this, pkg);
            return false;
        }

        return true;
    }

    private void lock(String pkg) {
        Log.w(TAG, "END REACHED pkg=" + pkg);

        long until = System.currentTimeMillis()
                + EndPrefs.cooldownMinutes(this) * 60_000L;

        EndPrefs.setProgress(this, pkg, EndPrefs.screens(this));
        EndPrefs.setLockedUntil(this, pkg, until);
        EndPrefs.setLastActivity(this, pkg, System.currentTimeMillis());

        showBadge(pkg);

        if (Build.VERSION.SDK_INT >= 34) {
            setMotionGuard(true);
        }
    }

    /**
     * Older Android can't use the Android 14 MotionEvent gate.
     * This fallback lets the gesture happen and immediately pushes content back.
     * v0.7's exact "wall before new content" behaviour is Android 14+.
     */
    private void legacyBounceBackIfNeeded(AccessibilityEvent event, String pkg, long when) {
        int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            int dy = event.getScrollDeltaY();

            if (dy > 0) {
                dispatchBounceBack();
                return;
            }

            if (dy == 0) {
                long last = lastFallbackBurstAt.getOrDefault("locked:" + pkg, 0L);

                if (when - last > 800L) {
                    lastFallbackBurstAt.put("locked:" + pkg, when);
                    dispatchBounceBack();
                }
            }
        }
    }

    private void dispatchBounceBack() {
        if (Build.VERSION.SDK_INT < 24 || replayInProgress) return;

        float x = screenWidth * 0.5f;
        float y0 = screenHeight * 0.36f;
        float y1 = screenHeight * 0.73f;

        Path path = new Path();
        path.moveTo(x, y0);
        path.lineTo(x, y1);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 220))
                .build();

        replayInProgress = true;

        boolean accepted = dispatchGesture(
                gesture,
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription d) {
                        replayInProgress = false;
                    }

                    @Override public void onCancelled(GestureDescription d) {
                        replayInProgress = false;
                    }
                },
                handler
        );

        if (!accepted) replayInProgress = false;
    }

    /**
     * Visual only. It never consumes touch.
     * The exact END mark is the cropped wordmark from mockup direction #2.
     */
    private void showBadge(String pkg) {
        if (windowManager == null) return;

        if (badge != null && pkg.equals(badgePackage)) {
            handler.removeCallbacks(tick);
            handler.post(tick);
            return;
        }

        hideBadge();
        badgePackage = pkg;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClickable(false);
        root.setFocusable(false);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), dp(8), dp(13), dp(8));

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.argb(238, 5, 5, 5));
        pillBg.setCornerRadius(dp(18));
        pillBg.setStroke(dp(1), Color.argb(105, 216, 255, 0));
        pill.setBackground(pillBg);

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.end_wordmark_mockup);
        mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mark.setContentDescription("END");
        pill.addView(mark, new LinearLayout.LayoutParams(dp(96), dp(30)));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(55, 55, 55));

        LinearLayout.LayoutParams dividerLp =
                new LinearLayout.LayoutParams(dp(1), dp(25));
        dividerLp.leftMargin = dp(10);
        dividerLp.rightMargin = dp(10);
        pill.addView(divider, dividerLp);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);

        badgeState = text("END OF FEED", ACID, 9, Typeface.BOLD);
        badgeState.setGravity(Gravity.START);
        badgeState.setLetterSpacing(.12f);
        status.addView(badgeState);

        countdown = text("--:--", TEXT, 12, Typeface.BOLD);
        countdown.setGravity(Gravity.START);

        LinearLayout.LayoutParams countdownLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        countdownLp.topMargin = dp(1);
        status.addView(countdown, countdownLp);

        pill.addView(status);

        FrameLayout.LayoutParams pillLp =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
                );

        // Keep it above common app navigation bars instead of sitting on them.
        pillLp.bottomMargin = dp(82);
        root.addView(pill, pillLp);

        WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                );

        lp.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(root, lp);
            badge = root;
            Log.i(TAG, "BADGE SHOWN pkg=" + pkg);
            handler.post(tick);
        } catch (Exception e) {
            Log.e(TAG, "BADGE FAILED pkg=" + pkg, e);
            badge = null;
            badgePackage = null;
        }
    }

    private void pulseBadge() {
        if (badge == null) return;

        badge.animate()
                .alpha(0.58f)
                .setDuration(75)
                .withEndAction(() ->
                        badge.animate()
                                .alpha(1f)
                                .setDuration(130)
                                .start()
                )
                .start();

        if (badgeState != null) {
            badgeState.setText("THAT'S THE END");
            handler.postDelayed(() -> {
                if (badgeState != null) badgeState.setText("END OF FEED");
            }, 800L);
        }
    }

    private void hideBadge() {
        handler.removeCallbacks(tick);

        if (badge != null && windowManager != null) {
            try {
                windowManager.removeView(badge);
            } catch (Exception ignored) {}
        }

        badge = null;
        countdown = null;
        badgeState = null;
        badgePackage = null;
    }

    private TextView text(String value, int color, int sp, int style) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(sp);
        v.setTypeface(Typeface.create("sans", style));
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private String formatRemaining(long ms) {
        long totalSeconds = Math.max(0, (ms + 999) / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("Back in %d:%02d", minutes, seconds);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String eventTypeName(int type) {
        switch (type) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED: return "VIEW_CLICKED";
            case AccessibilityEvent.TYPE_VIEW_LONG_CLICKED: return "VIEW_LONG_CLICKED";
            case AccessibilityEvent.TYPE_VIEW_SELECTED: return "VIEW_SELECTED";
            case AccessibilityEvent.TYPE_VIEW_FOCUSED: return "VIEW_FOCUSED";
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED: return "VIEW_TEXT_CHANGED";
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: return "WINDOW_STATE_CHANGED";
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED: return "NOTIFICATION_STATE_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_SCROLLED: return "VIEW_SCROLLED";
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: return "WINDOW_CONTENT_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED: return "TEXT_SELECTION_CHANGED";
            case AccessibilityEvent.TYPE_ANNOUNCEMENT: return "ANNOUNCEMENT";
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: return "ACCESSIBILITY_FOCUSED";
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: return "ACCESSIBILITY_FOCUS_CLEARED";
            case AccessibilityEvent.TYPE_TOUCH_INTERACTION_START: return "TOUCH_INTERACTION_START";
            case AccessibilityEvent.TYPE_TOUCH_INTERACTION_END: return "TOUCH_INTERACTION_END";
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED: return "WINDOWS_CHANGED";
            default: return "TYPE_0x" + Integer.toHexString(type);
        }
    }

    @Override
    public void onInterrupt() {
        hideBadge();
        setMotionGuard(false);
    }

    @Override
    public void onDestroy() {
        hideBadge();
        setMotionGuard(false);
        super.onDestroy();
    }
}
