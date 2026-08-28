package app.willswitch.end;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
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
 * END v0.9.
 *
 * The wall no longer works by capturing the touchscreen.
 *
 * v0.8 armed setMotionEventSources at the limit and injected every allowed
 * gesture back with dispatchGesture. Android gives no way to pass a captured
 * event through selectively, so everything had to be re-created, and on
 * devices where an injected stroke does not reach the app while the same
 * service is capturing, the app simply freezes. That is what happened in
 * Brave: the feed stopped, but so did scrolling back, tapping and everything
 * else.
 *
 * Instead the feed is pinned. Touch is never intercepted, so scrolling back,
 * tapping and navigation stay completely native. When the feed is pushed past
 * the point where the limit was reached, END scrolls it back with an
 * accessibility action, the way an ordinary list bounces when it runs out.
 * A banner across the bottom says the stream has ended.
 *
 * This also drops the Android 14 requirement. The wall now works on Android 9
 * and up.
 */
public class EndAccessibilityService extends AccessibilityService {

    static final String TAG = "ENDDBG";

    private static final int TEXT = Color.rgb(245, 245, 245);
    private static final int ACID = Color.rgb(216, 255, 0);

    private static final long URL_POLL_MS = 1200L;
    private static final long BOUNCE_MIN_GAP_MS = 450L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, FeedState> feeds = new HashMap<>();

    private EndPrefs.Snapshot config;
    private SharedPreferences.OnSharedPreferenceChangeListener configListener;

    private WindowManager windowManager;
    private View banner;
    private TextView bannerHeadline;
    private TextView bannerCountdown;
    private String bannerKey;

    private int screenHeight = 1;
    private float density = 1f;

    private String foregroundPackage;
    private String activeHost;
    private String activeKey;
    private long lastUrlPollAt;
    private long lastBounceAt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (bannerKey == null) return;

            FeedState state = feeds.get(bannerKey);
            long remaining = state == null
                    ? 0L
                    : state.lockedUntil - System.currentTimeMillis();

            if (remaining <= 0) {
                unlock(bannerKey);
                return;
            }

            if (bannerCountdown != null) {
                bannerCountdown.setText(formatRemaining(remaining));
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        config = EndPrefs.snapshot(this);
        readMetrics();

        configListener = (prefs, key) ->
                config = EndPrefs.snapshot(EndAccessibilityService.this);
        EndPrefs.prefs(this).registerOnSharedPreferenceChangeListener(configListener);

        Log.i(TAG, "SERVICE CONNECTED height=" + screenHeight
                + " limit=" + config.screens
                + " apps=" + config.apps.size());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        readMetrics();
    }

    private void readMetrics() {
        screenHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        density = getResources().getDisplayMetrics().density;
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
            onForegroundChanged(pkg, now);
            return;
        }

        if (!pkg.equals(foregroundPackage)) return;

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (Feeds.isBrowser(pkg)) pollBrowserHost(pkg, now);
            if (activeKey != null) onContentChanged(stateFor(activeKey), now);
            return;
        }

        if (type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        if (activeKey == null) return;

        onScrolled(stateFor(activeKey), event, now);
    }

    private void onScrolled(FeedState state, AccessibilityEvent event, long now) {
        if (state.isLocked(now)) {
            state.sawScrollEvent = true;
            if (state.pastWall(event)) bounce(event, state, now);
            return;
        }

        expireStaleProgress(state, now);

        float contribution = state.measureScroll(event, screenHeight, now);
        if (contribution <= 0f) return;

        addProgress(state, contribution, now);
    }

    private void onContentChanged(FeedState state, long now) {
        if (state.isLocked(now)) {
            if (state.burstSuggestsAdvance(now)) bounce(null, state, now);
            return;
        }

        float contribution = state.measureContentBurst(now);
        if (contribution <= 0f) return;

        expireStaleProgress(state, now);
        addProgress(state, contribution, now);
    }

    private void addProgress(FeedState state, float contribution, long now) {
        state.progress += contribution;
        state.lastActivityAt = now;

        Log.i(TAG, "PROGRESS key=" + state.key
                + " +" + contribution
                + " => " + state.progress
                + " / " + config.screens);

        if (state.progress >= config.screens) lock(state, now);
    }

    /**
     * Push the feed back to where the wall stands. This is the whole wall: no
     * touch is intercepted, so everything else in the app keeps working.
     */
    private void bounce(AccessibilityEvent event, FeedState state, long now) {
        if (now - lastBounceAt < BOUNCE_MIN_GAP_MS) return;
        lastBounceAt = now;

        AccessibilityNodeInfo target = null;
        AccessibilityNodeInfo root = null;

        try {
            if (event != null) target = scrollableFrom(event.getSource());
            if (target == null) {
                root = getRootInActiveWindow();
                target = findScrollable(root, 0);
            }

            if (target == null) {
                Log.w(TAG, "WALL no scrollable node key=" + state.key);
                return;
            }

            boolean done = target.performAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            Log.i(TAG, "WALL pushed back key=" + state.key + " ok=" + done);
            pulseBanner();
        } catch (Exception e) {
            Log.w(TAG, "WALL bounce failed key=" + state.key, e);
        } finally {
            if (target != null) target.recycle();
            if (root != null && root != target) root.recycle();
        }
    }

    private AccessibilityNodeInfo scrollableFrom(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int guard = 0;

        while (current != null && guard++ < 12) {
            if (current.isScrollable()) return current;
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }

        if (current != null) current.recycle();
        return null;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 14) return null;
        if (node.isScrollable()) return node;

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;

            AccessibilityNodeInfo found = findScrollable(child, depth + 1);
            if (found != null) {
                if (found != child) child.recycle();
                return found;
            }
            child.recycle();
        }
        return null;
    }

    // ------------------------------------------------------------- foreground

    private void onForegroundChanged(String pkg, long now) {
        if (Feeds.SYSTEM_UI.equals(pkg)) return;

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
            state.onEntered(now);
            expireStaleProgress(state, now);
            Log.i(TAG, "FEED key=" + activeKey
                    + " progress=" + state.progress
                    + " locked=" + state.isLocked(now));
        }

        if (activeKey == null || !activeKey.equals(bannerKey)) hideBanner();
        if (activeKey != null && stateFor(activeKey).isLocked(now)) showBanner(activeKey);
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
        state.markWall();
        EndPrefs.setLockedUntil(this, state.key, state.lockedUntil);

        Log.w(TAG, "END REACHED key=" + state.key);
        showBanner(state.key);
    }

    private void unlock(String key) {
        FeedState state = feeds.get(key);
        if (state != null) state.clearLock();
        EndPrefs.clearLock(this, key);
        hideBanner();
        Log.i(TAG, "END CLEARED key=" + key);
    }

    // ----------------------------------------------------------------- banner

    /**
     * A banner across the bottom of the stream, not a floating pill and not a
     * dialog. It never takes touch, so whatever sits underneath stays usable.
     */
    private void showBanner(String key) {
        if (windowManager == null) return;

        if (banner != null && key.equals(bannerKey)) {
            handler.removeCallbacks(tick);
            handler.post(tick);
            return;
        }

        hideBanner();
        bannerKey = key;

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(12), dp(20), dp(14));
        bar.setBackgroundColor(Color.argb(247, 6, 6, 6));

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.end_wordmark);
        mark.setScaleType(ImageView.ScaleType.FIT_START);
        mark.setContentDescription("END");
        bar.addView(mark, new LinearLayout.LayoutParams(dp(74), dp(24)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);

        bannerHeadline = label(headlineFor(key), ACID, 10, Typeface.BOLD);
        bannerHeadline.setLetterSpacing(.14f);
        copy.addView(bannerHeadline);

        bannerCountdown = label("--:--", TEXT, 13, Typeface.BOLD);
        LinearLayout.LayoutParams countdownLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        countdownLp.topMargin = dp(2);
        copy.addView(bannerCountdown, countdownLp);

        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        copyLp.leftMargin = dp(16);
        bar.addView(copy, copyLp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;

        try {
            windowManager.addView(bar, lp);
            banner = bar;
            Log.i(TAG, "BANNER SHOWN key=" + key);
            handler.post(tick);
        } catch (Exception e) {
            Log.e(TAG, "BANNER FAILED key=" + key, e);
            banner = null;
            bannerKey = null;
        }
    }

    private String headlineFor(String key) {
        String pkg = Feeds.packageOfKey(key);
        if (key.equals(pkg)) return "YOU HAVE REACHED THE END";
        return key.substring(pkg.length() + 1).toUpperCase() + " HAS ENDED";
    }

    private void pulseBanner() {
        if (banner == null || bannerHeadline == null) return;

        final String original = bannerHeadline.getText().toString();
        bannerHeadline.setText("NOTHING BELOW THIS");

        banner.animate()
                .alpha(0.55f)
                .setDuration(70)
                .withEndAction(() -> banner.animate().alpha(1f).setDuration(140).start())
                .start();

        handler.postDelayed(() -> {
            if (bannerHeadline != null) bannerHeadline.setText(original);
        }, 900L);
    }

    private void hideBanner() {
        handler.removeCallbacks(tick);

        if (banner != null && windowManager != null) {
            try {
                windowManager.removeView(banner);
            } catch (Exception ignored) {
                // The window was already gone.
            }
        }

        banner = null;
        bannerHeadline = null;
        bannerCountdown = null;
        bannerKey = null;
    }

    private TextView label(String value, int color, int sp, int style) {
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
        return String.format("New feed in %d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    // --------------------------------------------------------------- teardown

    @Override
    public void onInterrupt() {
        hideBanner();
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
        hideBanner();

        if (configListener != null) {
            EndPrefs.prefs(this)
                    .unregisterOnSharedPreferenceChangeListener(configListener);
            configListener = null;
        }
    }
}
