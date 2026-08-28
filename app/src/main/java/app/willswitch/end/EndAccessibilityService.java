package app.willswitch.end;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * END v0.8.
 *
 * Three things changed relative to v0.7:
 *
 * 1. Nothing on the input or event path touches disk. Settings live in a
 *    cached snapshot, feed progress lives in memory, and only the lock
 *    transition is persisted.
 * 2. Allowed gestures are mirrored into the app while the finger is still
 *    moving, instead of being replayed as one straight line after release.
 * 3. The motion guard is armed on state transitions only, not twice per
 *    gesture, which removes the setServiceInfo churn and the race that let
 *    forward swipes leak through.
 */
public class EndAccessibilityService extends AccessibilityService
        implements GestureMirror.Listener {

    static final String TAG = "ENDDBG";

    private static final int TEXT = Color.rgb(245, 245, 245);
    private static final int ACID = Color.rgb(216, 255, 0);

    private static final int BASE_EVENT_TYPES =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_SCROLLED;

    /** Only subscribed while a browser is in front, and only to find the URL. */
    private static final int BROWSER_EVENT_TYPES =
            BASE_EVENT_TYPES | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;

    private static final long URL_POLL_MS = 1200L;
    private static final long SYSTEM_UI_FALLBACK_MS = 20_000L;
    private static final long PROBE_WINDOW_MS = 800L;
    private static final int PROBE_FAILURES_BEFORE_FALLBACK = 3;

    private static final int DECISION_UNDECIDED = 0;
    private static final int DECISION_ALLOW = 1;
    private static final int DECISION_BLOCK = 2;
    private static final int DECISION_EDGE = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, FeedState> feeds = new HashMap<>();

    private EndPrefs.Snapshot config;
    private SharedPreferences.OnSharedPreferenceChangeListener configListener;

    private WindowManager windowManager;
    private View badge;
    private TextView countdown;
    private TextView badgeState;
    private String badgeKey;

    private GestureMirror mirror;

    private int screenWidth = 1;
    private int screenHeight = 1;
    private float density = 1f;

    private String foregroundPackage;
    private String activeHost;
    private String activeKey;
    private boolean systemUiOnTop;
    private long lastUrlPollAt;

    private int appliedEventTypes = -1;
    private int appliedMotionSources = -1;

    // Current captured gesture.
    private int decision = DECISION_UNDECIDED;
    private float downX;
    private float downY;
    private float lastPointX;
    private float lastPointY;
    private long downAt;
    private boolean multiTouch;

    // Mirror capability probe.
    private boolean probePending;
    private long probeStartedAt;
    private int probeFailures;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (badgeKey == null) return;

            FeedState state = feeds.get(badgeKey);
            long remaining = state == null
                    ? 0L
                    : state.lockedUntil - System.currentTimeMillis();

            if (remaining <= 0) {
                unlock(badgeKey);
                return;
            }

            if (countdown != null) countdown.setText(formatRemaining(remaining));
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable systemUiFallback = new Runnable() {
        @Override public void run() {
            systemUiOnTop = false;
            syncServiceInfo();
        }
    };

    private final Runnable probeTimeout = new Runnable() {
        @Override public void run() {
            if (!probePending) return;
            probePending = false;
            probeFailures++;
            Log.w(TAG, "MIRROR probe failed (" + probeFailures + ")");

            if (probeFailures >= PROBE_FAILURES_BEFORE_FALLBACK
                    && config != null
                    && config.mirrorMode != EndPrefs.MIRROR_RELEASE_GUARD) {
                Log.w(TAG, "MIRROR switching to release-guard mode: injected "
                        + "strokes are not reaching the app");
                EndPrefs.setMirrorMode(EndAccessibilityService.this,
                        EndPrefs.MIRROR_RELEASE_GUARD);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        config = EndPrefs.snapshot(this);
        mirror = new GestureMirror(this, handler, this);
        readMetrics();

        configListener = (prefs, key) -> {
            config = EndPrefs.snapshot(EndAccessibilityService.this);
            syncServiceInfo();
        };
        EndPrefs.prefs(this).registerOnSharedPreferenceChangeListener(configListener);

        appliedEventTypes = -1;
        appliedMotionSources = -1;
        syncServiceInfo();

        Log.i(TAG, "SERVICE CONNECTED screen=" + screenWidth + "x" + screenHeight
                + " mirrorMode=" + config.mirrorMode);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        readMetrics();
    }

    private void readMetrics() {
        screenWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        screenHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        density = getResources().getDisplayMetrics().density;
        if (mirror != null) mirror.setBounds(screenWidth, screenHeight);
    }

    // ---------------------------------------------------------------- events

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || config == null) return;

        CharSequence packageName = event.getPackageName();
        if (packageName == null) return;

        String pkg = packageName.toString();
        if (pkg.equals(getPackageName())) return;

        int type = event.getEventType();
        long now = System.currentTimeMillis();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onForegroundChanged(pkg);
            return;
        }

        if (probePending && pkg.equals(foregroundPackage)) confirmMirror();

        if (!pkg.equals(foregroundPackage)) return;

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (Feeds.isBrowser(pkg)) pollBrowserHost(pkg, now);
            return;
        }

        if (type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        if (activeKey == null) return;

        FeedState state = stateFor(activeKey);
        if (state.isLocked(now)) return;

        expireStaleProgress(state, now);

        float contribution = state.measure(event, screenHeight, now);
        if (contribution <= 0f) return;

        state.progress += contribution;
        state.lastActivityAt = now;

        Log.i(TAG, "PROGRESS key=" + activeKey
                + " +" + contribution
                + " => " + state.progress
                + " / " + config.screens);

        if (state.progress >= config.screens) lock(state, now);
    }

    private void onForegroundChanged(String pkg) {
        if (Feeds.SYSTEM_UI.equals(pkg)) {
            systemUiOnTop = true;
            handler.removeCallbacks(systemUiFallback);
            handler.postDelayed(systemUiFallback, SYSTEM_UI_FALLBACK_MS);
            syncServiceInfo();
            return;
        }

        systemUiOnTop = false;
        handler.removeCallbacks(systemUiFallback);

        boolean changed = !pkg.equals(foregroundPackage);
        foregroundPackage = pkg;

        if (changed) {
            activeHost = null;
            lastUrlPollAt = 0L;
            cancelGesture();
        }

        if (Feeds.isBrowser(pkg) && config.browserSites) {
            pollBrowserHost(pkg, System.currentTimeMillis());
        } else {
            recomputeActiveKey();
        }
    }

    /**
     * Reads the address bar of the foreground browser and keeps only the host.
     * The page itself is never queried. Throttled so a busy page cannot turn
     * this into a per-frame tree walk.
     */
    private void pollBrowserHost(String pkg, long now) {
        if (!config.browserSites) return;
        if (now - lastUrlPollAt < URL_POLL_MS) return;
        lastUrlPollAt = now;

        String viewId = Feeds.urlBarId(pkg);
        if (viewId == null) return;

        AccessibilityNodeInfo root = null;
        String host = null;

        try {
            root = getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> bars =
                        root.findAccessibilityNodeInfosByViewId(viewId);
                if (bars != null) {
                    for (AccessibilityNodeInfo bar : bars) {
                        if (bar == null) continue;
                        if (host == null) host = Feeds.hostOf(bar.getText());
                        bar.recycle();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "URL read failed pkg=" + pkg, e);
        } finally {
            if (root != null) root.recycle();
        }

        // While the user is typing, the bar holds free text. Keep the previous
        // host rather than dropping protection mid-session.
        if (host != null) activeHost = Feeds.matchEnabledHost(host, config.hosts);
        recomputeActiveKey();
    }

    private void recomputeActiveKey() {
        String previous = activeKey;

        if (foregroundPackage == null) {
            activeKey = null;
        } else if (Feeds.isBrowser(foregroundPackage)) {
            activeKey = activeHost == null
                    ? null
                    : Feeds.feedKey(foregroundPackage, activeHost);
        } else if (config.isProtectedApp(foregroundPackage)) {
            activeKey = Feeds.feedKey(foregroundPackage, null);
        } else {
            activeKey = null;
        }

        if (activeKey != null && !activeKey.equals(previous)) {
            FeedState state = stateFor(activeKey);
            expireStaleProgress(state, System.currentTimeMillis());
        }

        if (activeKey == null || !activeKey.equals(badgeKey)) hideBadge();

        if (activeKey != null && stateFor(activeKey).isLocked(System.currentTimeMillis())) {
            showBadge(activeKey);
        }

        syncServiceInfo();
    }

    private FeedState stateFor(String key) {
        FeedState state = feeds.get(key);
        if (state != null) return state;

        String pkg = Feeds.packageOfKey(key);
        String host = key.equals(pkg) ? null : key.substring(pkg.length() + 1);
        state = new FeedState(key, Feeds.profileFor(pkg, host));
        state.lockedUntil = EndPrefs.lockedUntil(this, key);
        feeds.put(key, state);
        return state;
    }

    private void expireStaleProgress(FeedState state, long now) {
        if (state.progress <= 0f || state.lastActivityAt <= 0L) return;
        if (now - state.lastActivityAt >= config.cooldownMillis()) state.reset();
    }

    private void lock(FeedState state, long now) {
        state.progress = config.screens;
        state.lockedUntil = now + config.cooldownMillis();
        state.lastActivityAt = now;
        EndPrefs.setLockedUntil(this, state.key, state.lockedUntil);

        Log.w(TAG, "END REACHED key=" + state.key);

        showBadge(state.key);
        syncServiceInfo();
    }

    private void unlock(String key) {
        FeedState state = feeds.get(key);
        if (state != null) state.clearLock();
        EndPrefs.clearLock(this, key);
        hideBadge();
        syncServiceInfo();
    }

    // ------------------------------------------------------------ guard sync

    /**
     * Single place where the service configuration is written. v0.7 called
     * setServiceInfo twice per allowed gesture; this runs on foreground and
     * lock transitions only, and skips the binder call when nothing changed.
     */
    private void syncServiceInfo() {
        if (config == null) return;

        boolean browserInFront = foregroundPackage != null
                && Feeds.isBrowser(foregroundPackage)
                && config.browserSites;

        int eventTypes = browserInFront ? BROWSER_EVENT_TYPES : BASE_EVENT_TYPES;

        boolean guard = Build.VERSION.SDK_INT >= 34
                && !systemUiOnTop
                && activeKey != null
                && config.mirrorMode != EndPrefs.MIRROR_RELEASE_GUARD
                && stateFor(activeKey).isLocked(System.currentTimeMillis());

        // In release-guard mode the guard is still needed to see the gesture,
        // it is just dropped as soon as a gesture is classified as allowed.
        if (Build.VERSION.SDK_INT >= 34
                && config.mirrorMode == EndPrefs.MIRROR_RELEASE_GUARD
                && !systemUiOnTop
                && activeKey != null
                && stateFor(activeKey).isLocked(System.currentTimeMillis())) {
            guard = true;
        }

        applyServiceInfo(eventTypes, guard ? InputDevice.SOURCE_TOUCHSCREEN : 0);
    }

    private void applyServiceInfo(int eventTypes, int motionSources) {
        if (eventTypes == appliedEventTypes && motionSources == appliedMotionSources) {
            return;
        }

        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;

        info.eventTypes = eventTypes;
        if (Build.VERSION.SDK_INT >= 34) info.setMotionEventSources(motionSources);

        try {
            setServiceInfo(info);
        } catch (Exception e) {
            Log.w(TAG, "SERVICE INFO update failed", e);
            return;
        }

        appliedEventTypes = eventTypes;
        appliedMotionSources = motionSources;

        Log.i(TAG, "SERVICE INFO events=0x" + Integer.toHexString(eventTypes)
                + " guard=" + (motionSources != 0)
                + " key=" + activeKey);
    }

    private void releaseGuardForGesture() {
        applyServiceInfo(appliedEventTypes, 0);
    }

    // --------------------------------------------------------------- gestures

    @Override
    public void onMotionEvent(MotionEvent event) {
        if (Build.VERSION.SDK_INT < 34 || event == null || config == null) return;
        if (appliedMotionSources == 0) return;

        String key = activeKey;
        if (key == null) return;

        FeedState state = feeds.get(key);
        if (state == null || !state.isLocked(System.currentTimeMillis())) {
            syncServiceInfo();
            return;
        }

        float x = event.getRawX();
        float y = event.getRawY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startGesture(x, y, event.getEventTime());
                return;

            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                if (decision == DECISION_UNDECIDED) allow(x, y);
                return;

            case MotionEvent.ACTION_MOVE:
                onGestureMove(x, y, event.getEventTime());
                return;

            case MotionEvent.ACTION_UP:
                onGestureUp(x, y, event.getEventTime());
                return;

            case MotionEvent.ACTION_CANCEL:
                cancelGesture();
                return;

            default:
        }
    }

    private void startGesture(float x, float y, long eventTime) {
        downX = x;
        downY = y;
        lastPointX = x;
        lastPointY = y;
        downAt = eventTime;
        multiTouch = false;
        decision = inSystemZone(x, y) ? DECISION_EDGE : DECISION_UNDECIDED;
    }

    private void onGestureMove(float x, float y, long eventTime) {
        lastPointX = x;
        lastPointY = y;

        if (decision == DECISION_BLOCK || decision == DECISION_EDGE) return;

        if (decision == DECISION_UNDECIDED) {
            classify(x, y, eventTime);
            return;
        }

        // Already mirroring. A swipe that started sideways or downward but ends
        // up well above its origin is still a request for new content.
        if (!multiTouch && y - downY <= -dp(48)) {
            Log.i(TAG, "FINITE WALL breach during allowed gesture key=" + activeKey);
            decision = DECISION_BLOCK;
            mirror.abort();
            pulseBadge();
            return;
        }

        mirror.move(x, y);
    }

    private void classify(float x, float y, long eventTime) {
        float dx = x - downX;
        float dy = y - downY;
        float ax = Math.abs(dx);
        float ay = Math.abs(dy);
        float slop = dp(10);
        long held = eventTime - downAt;

        if (ax < slop && ay < slop) {
            // Long press and drag must keep working, so open the mirror once
            // the finger has clearly been held in place.
            if (held >= 320L) allow(x, y);
            return;
        }

        if (ax > ay) {
            allow(x, y);
            return;
        }

        if (dy > 0) {
            allow(x, y);
            return;
        }

        Log.i(TAG, "FINITE WALL blocked forward swipe key=" + activeKey
                + " dy=" + dy);
        decision = DECISION_BLOCK;
        pulseBadge();
    }

    private void allow(float x, float y) {
        decision = DECISION_ALLOW;

        if (config.mirrorMode == EndPrefs.MIRROR_RELEASE_GUARD) {
            // Injection does not survive our own capture on this device, so the
            // only workable option is to stop capturing for the rest of the
            // gesture and let the finger through natively.
            releaseGuardForGesture();
            return;
        }

        long travelled = Math.max(1L, SystemClock.uptimeMillis() - downAt);
        mirror.begin(downX, downY, x, y, travelled);
    }

    private void onGestureUp(float x, float y, long eventTime) {
        switch (decision) {
            case DECISION_ALLOW:
                if (mirror.isOpen()) {
                    mirror.move(x, y);
                    mirror.finish();
                } else if (config.mirrorMode == EndPrefs.MIRROR_RELEASE_GUARD) {
                    handler.postDelayed(this::syncServiceInfo, 250L);
                }
                break;

            case DECISION_EDGE:
                resolveSystemGesture(x, y, eventTime);
                break;

            case DECISION_UNDECIDED:
                mirror.tap(x, y);
                break;

            default:
                break;
        }

        decision = DECISION_UNDECIDED;
        multiTouch = false;
    }

    private void cancelGesture() {
        if (mirror != null && mirror.isOpen()) mirror.abort();
        decision = DECISION_UNDECIDED;
        multiTouch = false;
    }

    private boolean inSystemZone(float x, float y) {
        return y <= screenHeight * 0.06f
                || y >= screenHeight * 0.94f
                || x <= dp(20)
                || x >= screenWidth - dp(20);
    }

    /**
     * Edge gestures cannot be reproduced by injecting a swipe: an injected
     * stroke from the left edge does not trigger Android's back navigation.
     * v0.7 replayed them as ordinary gestures, which is why navigation felt
     * broken after END. The global actions do the real thing.
     */
    private void resolveSystemGesture(float x, float y, long eventTime) {
        float dx = x - downX;
        float dy = y - downY;
        float threshold = dp(40);
        long held = eventTime - downAt;

        if (Math.abs(dx) < dp(10) && Math.abs(dy) < dp(10)) {
            mirror.tap(x, y);
            return;
        }

        if (downY <= screenHeight * 0.06f && dy > threshold) {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            return;
        }

        if (downY >= screenHeight * 0.94f && dy < -threshold) {
            performGlobalAction(held > 350L ? GLOBAL_ACTION_RECENTS : GLOBAL_ACTION_HOME);
            return;
        }

        if (downX <= dp(20) && dx > threshold) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            return;
        }

        if (downX >= screenWidth - dp(20) && dx < -threshold) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            return;
        }

        mirror.tap(x, y);
    }

    // ----------------------------------------------------------- mirror probe

    @Override
    public void onMirrorStarted() {
        if (config == null || config.mirrorMode == EndPrefs.MIRROR_CONFIRMED) return;
        probePending = true;
        probeStartedAt = SystemClock.uptimeMillis();
        handler.removeCallbacks(probeTimeout);
        handler.postDelayed(probeTimeout, PROBE_WINDOW_MS);
    }

    @Override
    public void onMirrorFinished(boolean ok) {
        if (!ok) Log.w(TAG, "MIRROR gesture aborted key=" + activeKey);
    }

    /**
     * The app reacted to something while we were mirroring, which means our
     * injected strokes do reach it even with the touchscreen captured.
     */
    private void confirmMirror() {
        if (SystemClock.uptimeMillis() - probeStartedAt > PROBE_WINDOW_MS) return;
        probePending = false;
        probeFailures = 0;
        handler.removeCallbacks(probeTimeout);

        if (config.mirrorMode != EndPrefs.MIRROR_CONFIRMED) {
            Log.i(TAG, "MIRROR confirmed: injected strokes reach the app");
            EndPrefs.setMirrorMode(this, EndPrefs.MIRROR_CONFIRMED);
        }
    }

    // ------------------------------------------------------------------ badge

    private void showBadge(String key) {
        if (windowManager == null) return;

        if (badge != null && key.equals(badgeKey)) {
            handler.removeCallbacks(tick);
            handler.post(tick);
            return;
        }

        hideBadge();
        badgeKey = key;

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dpi(12), dpi(8), dpi(13), dpi(8));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(238, 5, 5, 5));
        background.setCornerRadius(dp(18));
        background.setStroke(dpi(1), Color.argb(105, 216, 255, 0));
        pill.setBackground(background);

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.end_wordmark_mockup);
        mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mark.setContentDescription("END");
        pill.addView(mark, new LinearLayout.LayoutParams(dpi(96), dpi(30)));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(55, 55, 55));
        LinearLayout.LayoutParams dividerLp =
                new LinearLayout.LayoutParams(dpi(1), dpi(25));
        dividerLp.leftMargin = dpi(10);
        dividerLp.rightMargin = dpi(10);
        pill.addView(divider, dividerLp);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);

        badgeState = text(labelFor(key), ACID, 9, Typeface.BOLD);
        badgeState.setLetterSpacing(.12f);
        status.addView(badgeState);

        countdown = text("--:--", TEXT, 12, Typeface.BOLD);
        LinearLayout.LayoutParams countdownLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        countdownLp.topMargin = dpi(1);
        status.addView(countdown, countdownLp);

        pill.addView(status);

        // v0.7 used a full screen overlay for this pill, which forced an extra
        // composited layer over the whole display. A wrap_content window is
        // enough and costs nothing.
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dpi(82);

        try {
            windowManager.addView(pill, lp);
            badge = pill;
            Log.i(TAG, "BADGE SHOWN key=" + key);
            handler.post(tick);
        } catch (Exception e) {
            Log.e(TAG, "BADGE FAILED key=" + key, e);
            badge = null;
            badgeKey = null;
        }
    }

    private String labelFor(String key) {
        String pkg = Feeds.packageOfKey(key);
        if (key.equals(pkg)) return "END OF FEED";
        return key.substring(pkg.length() + 1).toUpperCase() + " ENDED";
    }

    private void pulseBadge() {
        if (badge == null) return;

        badge.animate()
                .alpha(0.58f)
                .setDuration(75)
                .withEndAction(() -> badge.animate().alpha(1f).setDuration(130).start())
                .start();

        if (badgeState != null) {
            final String original = badgeState.getText().toString();
            badgeState.setText("THAT'S THE END");
            handler.postDelayed(() -> {
                if (badgeState != null) badgeState.setText(original);
            }, 800L);
        }
    }

    private void hideBadge() {
        handler.removeCallbacks(tick);

        if (badge != null && windowManager != null) {
            try {
                windowManager.removeView(badge);
            } catch (Exception ignored) {
                // The window was already gone.
            }
        }

        badge = null;
        countdown = null;
        badgeState = null;
        badgeKey = null;
    }

    private TextView text(String value, int color, int sp, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setTypeface(Typeface.create("sans", style));
        view.setGravity(Gravity.START);
        return view;
    }

    private String formatRemaining(long ms) {
        long totalSeconds = Math.max(0, (ms + 999) / 1000);
        return String.format("Back in %d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private float dp(int value) {
        return value * density;
    }

    private int dpi(int value) {
        return Math.round(value * density);
    }

    // ---------------------------------------------------------------- teardown

    @Override
    public void onInterrupt() {
        cancelGesture();
        hideBadge();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        teardown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    private void teardown() {
        handler.removeCallbacksAndMessages(null);
        cancelGesture();
        hideBadge();

        if (configListener != null) {
            EndPrefs.prefs(this)
                    .unregisterOnSharedPreferenceChangeListener(configListener);
            configListener = null;
        }

        if (Build.VERSION.SDK_INT >= 34) applyServiceInfo(BASE_EVENT_TYPES, 0);
    }
}
