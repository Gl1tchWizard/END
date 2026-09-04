package app.willswitch.end;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * END 1.0.
 *
 * One rule: after the chosen amount of feed, the page stops.
 *
 * The stop is a wall. When a feed reaches its limit, a full screen END wall
 * is placed over it. The wall takes every touch itself, so nothing underneath
 * can scroll, and it stays until the countdown runs out or the user leaves.
 * Nothing is injected, nothing is captured, nothing races the app: a wall is
 * the only mechanism on Android that is exact on every device and every app.
 *
 * Counting uses accessibility scroll events, and for feeds that never report
 * scrolling a burst of content changes. Both are gated on a touch having just
 * happened, which a 1 px sensor window reports without ever seeing where.
 */
public class EndAccessibilityService extends AccessibilityService {

    static final String TAG = "ENDDBG";

    private static final int TEXT = Color.rgb(245, 245, 245);
    private static final int MUTED = Color.rgb(150, 150, 150);
    private static final int ACID = Color.rgb(216, 255, 0);

    private static final long URL_POLL_MS = 1200L;
    private static final long TOUCH_GATE_SCROLL_MS = 2000L;
    private static final long TOUCH_GATE_BURST_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, FeedState> feeds = new HashMap<>();

    private EndPrefs.Snapshot config;
    private SharedPreferences.OnSharedPreferenceChangeListener configListener;

    private WindowManager windowManager;
    private View sensor;
    private View wall;
    private View wallColumn;
    private TextView wallHeadline;
    private TextView wallCountdown;
    private String wallKey;

    private int screenHeight = 1;
    private int screenWidth = 1;
    private float density = 1f;

    private String foregroundPackage;
    private String activeHost;
    private String activeKey;
    private long lastUrlPollAt;
    private long lastTouchAt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (wallKey == null) return;

            FeedState state = feeds.get(wallKey);
            long remaining = state == null
                    ? 0L
                    : state.lockedUntil - System.currentTimeMillis();

            if (remaining <= 0) {
                unlock(wallKey);
                return;
            }

            if (wallCountdown != null) wallCountdown.setText(formatRemaining(remaining));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        config = EndPrefs.snapshot(this);
        readMetrics();
        attachSensor();

        configListener = (prefs, key) ->
                config = EndPrefs.snapshot(EndAccessibilityService.this);
        EndPrefs.prefs(this).registerOnSharedPreferenceChangeListener(configListener);

        Log.i(TAG, "SERVICE CONNECTED limit=" + config.screens
                + " cooldown=" + config.cooldownMinutes
                + " apps=" + config.apps.size());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        readMetrics();
    }

    private void readMetrics() {
        screenHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        screenWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        density = getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------- touch sensor

    /**
     * A one pixel, untouchable window that asks to be told about touches
     * outside itself. Android reports each touch down as ACTION_OUTSIDE with
     * no useful coordinates. That is exactly enough: it tells END the user is
     * moving the feed, not what they are doing with it.
     */
    private void attachSensor() {
        if (windowManager == null || sensor != null) return;

        View view = new View(this);
        view.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                lastTouchAt = System.currentTimeMillis();
            }
            return false;
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSPARENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(view, lp);
            sensor = view;
        } catch (Exception e) {
            Log.w(TAG, "SENSOR failed, counting without touch gate", e);
            lastTouchAt = Long.MAX_VALUE / 2;
        }
    }

    private boolean touchedWithin(long window, long now) {
        return now - lastTouchAt <= window;
    }

    // ---------------------------------------------------------------- events

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || config == null) return;

        CharSequence packageName = event.getPackageName();
        if (packageName == null) return;

        String pkg = packageName.toString();
        int type = event.getEventType();
        long now = System.currentTimeMillis();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onForegroundChanged(pkg, now);
            return;
        }

        if (!pkg.equals(foregroundPackage) || pkg.equals(getPackageName())) return;
        if (activeKey == null) {
            if (Feeds.isBrowser(pkg) && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                pollBrowserHost(pkg, now);
            }
            return;
        }

        FeedState state = stateFor(activeKey);

        if (state.isLocked(now)) {
            // Self healing: if the wall is somehow not up while a locked feed is
            // producing events, raise it again.
            if (wall == null) showWall(activeKey);
            if (Feeds.isBrowser(pkg) && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                pollBrowserHost(pkg, now);
            }
            return;
        }

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (!touchedWithin(TOUCH_GATE_SCROLL_MS, now)) return;
            expireStaleProgress(state, now);
            addProgress(state, state.measureScroll(event, screenHeight, now), now);
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (Feeds.isBrowser(pkg)) pollBrowserHost(pkg, now);
            if (!touchedWithin(TOUCH_GATE_BURST_MS, now)) return;
            float contribution = state.measureContentBurst(now);
            if (contribution > 0f) {
                expireStaleProgress(state, now);
                addProgress(state, contribution, now);
            }
        }
    }

    private void addProgress(FeedState state, float contribution, long now) {
        if (contribution <= 0f) return;

        state.progress += contribution;
        state.lastActivityAt = now;

        Log.i(TAG, "PROGRESS key=" + state.key
                + " +" + contribution
                + " => " + state.progress
                + " / " + config.screens);

        if (state.progress >= config.screens) lock(state, now);
    }

    // ------------------------------------------------------------- foreground

    private void onForegroundChanged(String pkg, long now) {
        if (Feeds.SYSTEM_UI.equals(pkg)) {
            // Shade, recents or a system dialog on top. Drop the wall so it
            // can never sit over system UI; the next event from the locked
            // feed raises it again.
            hideWall();
            return;
        }

        boolean changed = !pkg.equals(foregroundPackage);
        foregroundPackage = pkg;

        if (changed) {
            activeHost = null;
            lastUrlPollAt = 0L;
        }

        if (Feeds.isBrowser(pkg) && config.browserSites) {
            pollBrowserHost(pkg, now);
        } else {
            recomputeActiveKey(now);
        }
    }

    /**
     * Reads the address bar of the foreground browser and keeps only the host.
     * The page itself is never queried.
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
        recomputeActiveKey(now);
    }

    private void recomputeActiveKey(long now) {
        String previous = activeKey;

        if (foregroundPackage == null || foregroundPackage.equals(getPackageName())) {
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
            state.onEntered(now);
            expireStaleProgress(state, now);
            Log.i(TAG, "FEED key=" + activeKey
                    + " progress=" + state.progress
                    + " locked=" + state.isLocked(now));
        }

        if (activeKey == null || !activeKey.equals(wallKey)) hideWall();
        if (activeKey != null && stateFor(activeKey).isLocked(now)) showWall(activeKey);
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
        showWall(state.key);
    }

    private void unlock(String key) {
        FeedState state = feeds.get(key);
        if (state != null) state.clearLock();
        EndPrefs.clearLock(this, key);
        hideWall();
        Log.i(TAG, "END CLEARED key=" + key);
    }

    // ------------------------------------------------------------------- wall

    private void showWall(String key) {
        if (windowManager == null) return;

        if (wall != null && key.equals(wallKey)) {
            handler.removeCallbacks(tick);
            handler.post(tick);
            return;
        }

        hideWall();
        wallKey = key;

        final boolean inBrowser = Feeds.isBrowser(Feeds.packageOfKey(key));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.argb(244, 5, 5, 5));
        root.setClickable(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(32), 0, dp(32), 0);

        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.end_brand);
        brand.setAdjustViewBounds(true);
        brand.setScaleType(ImageView.ScaleType.FIT_CENTER);
        brand.setContentDescription("END, make feeds finite, by WillSwitch");
        column.addView(brand, new LinearLayout.LayoutParams(
                Math.round(screenWidth * 0.58f), ViewGroup.LayoutParams.WRAP_CONTENT));

        wallHeadline = label(headlineFor(key), ACID, 11, Typeface.BOLD);
        wallHeadline.setLetterSpacing(.18f);
        wallHeadline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams headlineLp = wrap();
        headlineLp.topMargin = dp(44);
        column.addView(wallHeadline, headlineLp);

        wallCountdown = label("--:--", TEXT, 24, Typeface.BOLD);
        wallCountdown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams countdownLp = wrap();
        countdownLp.topMargin = dp(10);
        column.addView(wallCountdown, countdownLp);

        TextView hint = label(inBrowser ? "Tap anywhere to go back" : "Tap anywhere to leave",
                MUTED, 12, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = wrap();
        hintLp.topMargin = dp(36);
        column.addView(hint, hintLp);

        FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(column, columnLp);
        wallColumn = column;

        root.setOnTouchListener(new WallTouch(inBrowser));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(root, lp);
            wall = root;
            Log.i(TAG, "WALL UP key=" + key);
            handler.post(tick);
        } catch (Exception e) {
            Log.e(TAG, "WALL FAILED key=" + key, e);
            wall = null;
            wallColumn = null;
            wallKey = null;
        }
    }

    /**
     * Owns every touch on the wall. A swipe moves the END card a little and
     * springs it back, the way the bottom of a finished list behaves. A tap
     * leaves the feed.
     */
    private final class WallTouch implements View.OnTouchListener {
        private final boolean inBrowser;
        private float downY;
        private long downAt;
        private boolean moved;

        WallTouch(boolean inBrowser) {
            this.inBrowser = inBrowser;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = event.getRawY();
                    downAt = event.getEventTime();
                    moved = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dy = event.getRawY() - downY;
                    if (Math.abs(dy) > dp(8)) moved = true;
                    float limit = dp(56);
                    float pull = Math.max(-limit, Math.min(limit, dy * 0.28f));
                    if (wallColumn != null) wallColumn.setTranslationY(pull);
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (wallColumn != null) {
                        wallColumn.animate().translationY(0f).setDuration(180).start();
                    }
                    boolean tap = !moved && event.getEventTime() - downAt < 350L;
                    if (tap && event.getActionMasked() == MotionEvent.ACTION_UP) leave();
                    return true;
                }

                default:
                    return true;
            }
        }

        private void leave() {
            Log.i(TAG, "WALL leave key=" + wallKey + " browser=" + inBrowser);
            performGlobalAction(inBrowser ? GLOBAL_ACTION_BACK : GLOBAL_ACTION_HOME);
        }
    }

    private String headlineFor(String key) {
        String pkg = Feeds.packageOfKey(key);
        if (key.equals(pkg)) return "YOU HAVE REACHED THE END";
        return key.substring(pkg.length() + 1).toUpperCase() + " HAS ENDED";
    }

    private void hideWall() {
        handler.removeCallbacks(tick);

        if (wall != null && windowManager != null) {
            try {
                windowManager.removeView(wall);
                Log.i(TAG, "WALL DOWN key=" + wallKey);
            } catch (Exception ignored) {
                // The window was already gone.
            }
        }

        wall = null;
        wallColumn = null;
        wallHeadline = null;
        wallCountdown = null;
        wallKey = null;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView label(String value, int color, int sp, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setTypeface(Typeface.create("sans", style));
        return view;
    }

    private String formatRemaining(long ms) {
        long totalSeconds = Math.max(0, (ms + 999) / 1000);
        return String.format("New feed in %d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    // --------------------------------------------------------------- teardown

    @Override
    public void onInterrupt() {
        hideWall();
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
        hideWall();

        if (sensor != null && windowManager != null) {
            try {
                windowManager.removeView(sensor);
            } catch (Exception ignored) {
                // Already gone.
            }
            sensor = null;
        }

        if (configListener != null) {
            EndPrefs.prefs(this)
                    .unregisterOnSharedPreferenceChangeListener(configListener);
            configListener = null;
        }
    }
}
