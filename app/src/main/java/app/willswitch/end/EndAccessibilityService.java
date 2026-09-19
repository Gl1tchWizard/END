package app.willswitch.end;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
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
 * END.
 *
 * One rule: after the chosen amount of feed, the scroll ends.
 *
 * Not a blocker. When a feed reaches its limit a transparent fence covers the
 * feed band, with a footer along its bottom edge saying the feed has ended.
 * The feed stays visible, taps go through to the app, the app's own bars and
 * Android's navigation stay untouched, and a swipe steps the feed back a page
 * so nothing that was seen is lost. Only going further is impossible.
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
    private long lastResolveAt;

    /**
     * Whether the touch sensor has ever reported anything. Until it has, the
     * touch gate is open: a sensor that stays silent on some device must not
     * be able to switch counting off entirely.
     */
    private boolean sensorAlive;

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
        RawLog.reset();
        readMetrics();
        attachSensor();

        configListener = (prefs, key) -> {
            config = EndPrefs.snapshot(EndAccessibilityService.this);
            if (EndPrefs.KEY_RESET.equals(key)) resetAllFeeds();
        };
        EndPrefs.prefs(this).registerOnSharedPreferenceChangeListener(configListener);

        Diag.connected = true;
        log("SERVICE CONNECTED limit=" + config.screens
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
                Diag.lastTouchAt = lastTouchAt;
                if (!sensorAlive) {
                    sensorAlive = true;
                    Diag.sensorAlive = true;
                    log("SENSOR alive, touch gate armed");
                }
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
            log("SENSOR failed, counting without touch gate", e);
            lastTouchAt = Long.MAX_VALUE / 2;
        }
    }

    private boolean touchedWithin(long window, long now) {
        if (!sensorAlive) return true;
        return now - lastTouchAt <= window;
    }

    // ---------------------------------------------------------------- events

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || config == null) return;

        if (RawLog.sync(this)) {
            RawLog.event(this, event, handler);
            if (RawLog.passive()) {
                if (wallKey != null) hideWall();
                return;
            }
            if (!RawLog.isCounted(event.getEventType())) return;
        }

        CharSequence packageName = event.getPackageName();
        if (packageName == null) return;

        String pkg = packageName.toString();
        int type = event.getEventType();
        long now = System.currentTimeMillis();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg.equals(getPackageName())) {
                // The wall and the sensor are windows of this package too.
                // Only the settings screen counts as END coming to the front;
                // anything else from our own package is us, not the user.
                CharSequence cls = event.getClassName();
                boolean settingsScreen = cls != null
                        && cls.toString().equals(MainActivity.class.getName());
                if (settingsScreen) onForegroundChanged(pkg, now);
                return;
            }
            onForegroundChanged(pkg, now);
            return;
        }

        if (pkg.equals(getPackageName())) return;

        if (!pkg.equals(foregroundPackage)) {
            // A keyboard or a system dialog may have taken the last window
            // state change. If a feed app is producing events, ask Android
            // which window is actually active and correct ourselves.
            if (isFeedPackage(pkg) && now - lastResolveAt > 1000L) {
                lastResolveAt = now;
                String active = activeWindowPackage();
                if (pkg.equals(active)) {
                    log("FOREGROUND corrected to " + pkg);
                    onForegroundChanged(pkg, now);
                }
            }
            if (!pkg.equals(foregroundPackage)) return;
        }

        if (activeKey == null) {
            if (Feeds.isBrowser(pkg) && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                pollBrowserHost(pkg, now);
            }
            return;
        }

        FeedState state = stateFor(activeKey);

        if (state.isLocked(now)) {
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                learnFeedNode(state, event, now);
                onScrolledWhileEnded(state, event, now);
            } else if (wall == null && curtain == null) {
                // Self healing: an ended feed is producing events and its end
                // is not on screen. Raise it.
                showWall(activeKey);
            }
            if (Feeds.isBrowser(pkg) && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                pollBrowserHost(pkg, now);
            }
            return;
        }

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            Diag.scrollEvents++;
            Diag.lastFeedEventAt = now;
            learnFeedNode(state, event, now);
            if (fromFeedNode(state, event)) state.observePosition(event);
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
                Diag.burstCounts++;
                Diag.lastFeedEventAt = now;
                expireStaleProgress(state, now);
                addProgress(state, contribution, now);
            }
        }
    }

    private long lastLearnAt;

    /**
     * Remember which scrollable carries the feed and where it sits on screen.
     * This is the only place that fetches nodes while the user scrolls, and it
     * runs at most once every two seconds: every node fetch is a synchronous
     * round trip into the app's own UI thread, and doing that per scroll
     * frame is exactly what makes an app feel less smooth.
     */
    private void learnFeedNode(FeedState state, AccessibilityEvent event, long now) {
        boolean firstTime = state.feedBounds == null;
        if (!firstTime && now - lastLearnAt < 2000L) return;
        lastLearnAt = now;

        AccessibilityNodeInfo source = null;
        AccessibilityNodeInfo root = null;
        try {
            source = event.getSource();
            if (source == null) {
                // Some apps send scroll events without a source. Take the
                // largest scrollable on screen instead.
                root = getRootInActiveWindow();
                source = largestScrollable(root);
                if (source == null) return;
            }

            String id = source.getViewIdResourceName();
            Rect bounds = new Rect();
            source.getBoundsInScreen(bounds);
            if (bounds.height() < dp(120)) return;

            if (state.feedViewId != null && id != null && !id.equals(state.feedViewId)
                    && state.isLocked(now)) {
                // Another list scrolled; the end belongs to the first one.
                return;
            }

            boolean changed = state.feedBounds == null || !bounds.equals(state.feedBounds)
                    || (id != null && !id.equals(state.feedViewId));
            state.feedViewId = id;
            state.feedBounds = bounds;
            CharSequence cls = event.getClassName();
            state.feedClassName = cls == null ? null : cls.toString();
            state.feedItemCount = event.getItemCount();
            if (changed) {
                log("FEED NODE " + id + " " + bounds.toShortString()
                        + " class=" + state.feedClassName + " items=" + state.feedItemCount);
                if (curtain != null && state.key.equals(wallKey)) relayoutCurtain(state);
            }
        } catch (Exception ignored) {
            // The node went away.
        } finally {
            if (source != null && source != root) source.recycle();
            if (root != null) root.recycle();
        }
    }

    /**
     * An ended feed that reports its position gets a curtain: a window that
     * takes no touches, opaque from the end of the feed downward, that follows
     * the feed's own scroll events.
     *
     * The curtain alone is not enough. If the feed keeps scrolling forward,
     * the curtain keeps rising and the screen goes black. So the end also
     * holds: every scroll event that moves the feed past the end is answered
     * immediately with a scroll back on the feed's own node. That is a hard
     * stop with one frame of give, not a fight, because it only ever reacts to
     * forward movement and never touches a backward scroll.
     */
    private void onScrolledWhileEnded(FeedState state, AccessibilityEvent event, long now) {
        if (!state.positionKnown) return;
        if (!fromFeedNode(state, event, now)) return;
        if (!state.observePosition(event)) return;

        if (curtain == null) {
            showWall(state.key);
            return;
        }

        updateCurtain(state);

        float over = overshootPixels(state);
        if (over > dp(2) && state.lastMoveForward) {
            holdAtEnd(state, over, now);
        } else if (over <= dp(2)) {
            holdFailures = 0;
            handler.removeCallbacks(rescue);
        }
    }

    private static final long HOLD_MIN_GAP_MS = 160L;
    private static final long RESCUE_DELAY_MS = 700L;
    private static final int HOLD_FAILURES_BEFORE_RESCUE = 2;

    private long lastHoldAt;
    private int holdFailures;

    /**
     * The feed moved past the end. Put it back, now. Rate limited so a fling
     * produces a few corrections instead of one per frame.
     */
    private void holdAtEnd(FeedState state, float overshoot, long now) {
        if (now - lastHoldAt < HOLD_MIN_GAP_MS) return;
        lastHoldAt = now;

        boolean accepted = scrollFeedBack(state);
        if (accepted) {
            holdFailures = 0;
            return;
        }

        // The app refuses the scroll action. A gesture cannot be injected
        // while a finger is down, so try once the feed is at rest.
        holdFailures++;
        if (holdFailures >= HOLD_FAILURES_BEFORE_RESCUE) {
            handler.removeCallbacks(rescue);
            handler.postDelayed(rescue, RESCUE_DELAY_MS);
        }
    }

    /**
     * Last resort for apps that refuse the scroll action: once the feed is at
     * rest past its end, drag it back with a slow injected swipe that ends at
     * zero velocity so nothing flings.
     */
    private final Runnable rescue = new Runnable() {
        @Override public void run() {
            FeedState state = wallKey == null ? null : feeds.get(wallKey);
            if (state == null || curtain == null || curtainParams == null) return;

            float over = overshootPixels(state);
            if (over <= dp(6)) return;

            float distance = Math.min(over + dp(12), curtainHeight * 0.7f);
            float x = curtainParams.x + curtainParams.width / 2f;
            float y0 = curtainParams.y + curtainHeight * 0.2f;
            float y1 = Math.min(realScreenHeight() - dp(24), y0 + distance);
            long duration = Math.max(160L, Math.min(400L, (long) (distance / 2.0f)));

            try {
                Path drag = new Path();
                drag.moveTo(x, y0);
                drag.lineTo(x, y1);
                GestureDescription.StrokeDescription main =
                        new GestureDescription.StrokeDescription(drag, 0, duration, true);

                Path tail = new Path();
                tail.moveTo(x, y1);
                tail.lineTo(x, y1 + 2f);
                final GestureDescription.StrokeDescription last =
                        main.continueStroke(tail, 0, 160L, false);

                dispatchGesture(new GestureDescription.Builder().addStroke(main).build(),
                        new GestureResultCallback() {
                            @Override public void onCompleted(GestureDescription d) {
                                try {
                                    dispatchGesture(new GestureDescription.Builder()
                                            .addStroke(last).build(), null, handler);
                                } catch (Exception e) {
                                    log("RESCUE tail failed", e);
                                }
                            }
                            @Override public void onCancelled(GestureDescription d) {
                                log("RESCUE cancelled, finger on screen");
                            }
                        }, handler);
                log("RESCUE drag " + Math.round(distance) + "px over " + duration + "ms");
            } catch (Exception e) {
                log("RESCUE failed", e);
            }
        }
    };

    /** Ask the feed's own node to scroll back one page. True if accepted. */
    private boolean scrollFeedBack(FeedState state) {
        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo node = null;
        try {
            root = getRootInActiveWindow();
            node = feedNode(state, root, -1f, -1f);
            if (node == null) return false;

            boolean done = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            if (!done && Build.VERSION.SDK_INT >= 29) {
                done = node.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId());
            }
            if (!holdLogged || !done) {
                log("HOLD scroll back ok=" + done + " overshoot=" + Math.round(overshootPixels(state)));
                holdLogged = true;
            }
            return done;
        } catch (Exception e) {
            log("HOLD failed", e);
            return false;
        } finally {
            if (node != null && node != root) node.recycle();
            if (root != null) root.recycle();
        }
    }

    private boolean holdLogged;

    private long lastVerifyAt;

    /**
     * Events from a different list in the same app say nothing about the feed,
     * and a stray one wrecks an accumulated position. The cheap signature,
     * class name and item count, decides normally; when an event claims a jump
     * larger than a screen, the source node is fetched to be sure. That check
     * runs at most a few times a second, so the scroll path stays free.
     */
    private boolean fromFeedNode(FeedState state, AccessibilityEvent event, long now) {
        if (state.feedClassName != null) {
            CharSequence cls = event.getClassName();
            if (cls != null && !cls.toString().equals(state.feedClassName)) return false;
        }

        int items = event.getItemCount();
        if (items >= 0 && state.feedItemCount >= 0) {
            int big = Math.max(items, state.feedItemCount);
            int small = Math.min(items, state.feedItemCount);
            if (small > 0 && big > small * 4 && big - small > 20) return false;
        }

        if (state.feedViewId == null) return true;

        int dy = event.getScrollDeltaY();
        boolean bigJump = dy != -1 && Math.abs(dy) > screenHeight;
        if (!bigJump || now - lastVerifyAt < 250L) return !bigJump;

        lastVerifyAt = now;
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return false;
        String id = source.getViewIdResourceName();
        source.recycle();
        boolean ours = id == null || id.equals(state.feedViewId);
        if (!ours) log("IGNORED jump of " + dy + "px from " + id);
        return ours;
    }

    private boolean fromFeedNode(FeedState state, AccessibilityEvent event) {
        return fromFeedNode(state, event, System.currentTimeMillis());
    }

    private long lastPullAt;

    /** Ask the feed's own node to scroll back. True if the app accepted. */
    private boolean pullBackToEnd(FeedState state, long now) {
        if (now - lastPullAt < 500L) return false;
        lastPullAt = now;

        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo node = null;
        try {
            root = getRootInActiveWindow();
            node = feedNode(state, root, -1f, -1f);
            if (node == null) return false;

            boolean done = false;
            if (state.profile == Feeds.PROFILE_ITEM && !state.positionInPixels && Build.VERSION.SDK_INT >= 23) {
                Bundle args = new Bundle();
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, Math.round(state.endPosition));
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0);
                done = node.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId(), args);
            }
            if (!done) done = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            log("PULL back to end ok=" + done + " overshoot=" + state.overshoot());
            return done;
        } catch (Exception e) {
            log("PULL failed", e);
            return false;
        } finally {
            if (node != null && node != root) node.recycle();
            if (root != null) root.recycle();
        }
    }

    private void addProgress(FeedState state, float contribution, long now) {
        if (contribution <= 0f) return;

        state.progress += contribution;
        state.lastActivityAt = now;
        Diag.lastProgress = state.progress;

        log("PROGRESS key=" + state.key
                + " +" + contribution
                + " => " + state.progress
                + " / " + config.screens);

        if (state.progress >= config.screens) lock(state, now);
    }

    // ------------------------------------------------------------- foreground

    private void onForegroundChanged(String pkg, long now) {
        // The shade and system dialogs draw above the wall on their own, and
        // recents is the launcher, which is a real foreground change. So
        // System UI is never a reason to touch the wall.
        if (Feeds.SYSTEM_UI.equals(pkg)) return;

        // Keyboards, permission dialogs and edge panels announce a window
        // state change too, but the app underneath is still the foreground.
        if (isTransientWindow(pkg)) return;

        boolean changed = !pkg.equals(foregroundPackage);
        foregroundPackage = pkg;
        Diag.foreground = pkg;

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

    private boolean isFeedPackage(String pkg) {
        return config.isProtectedApp(pkg) || Feeds.isBrowser(pkg);
    }

    private static boolean isTransientWindow(String pkg) {
        if (pkg == null) return true;
        if (pkg.equals("android")) return true;
        if (pkg.contains("inputmethod") || pkg.contains("keyboard")
                || pkg.contains("honeyboard") || pkg.contains("swiftkey")) return true;
        if (pkg.contains("permissioncontroller")) return true;
        if (pkg.contains("cocktailbar")) return true;
        return false;
    }

    /** Package of the window that actually has focus, or null if unknown. */
    private String activeWindowPackage() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return null;
            return root.getPackageName().toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (root != null) root.recycle();
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
            log("URL read failed pkg=" + pkg, e);
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
            if (state.isLocked(now)) state.assumeAtEnd();
            Diag.lastFeed = activeKey;
            Diag.lastProgress = state.progress;
            log("FEED key=" + activeKey
                    + " progress=" + state.progress
                    + " locked=" + state.isLocked(now));
        }

        if (activeKey == null || !activeKey.equals(wallKey)) {
            if (wallKey != null) log("WALL DOWN because feed is now " + activeKey);
            hideWall();
        }
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
        state.pinEnd();
        if (state.positionInPixels) state.endPosition += dp(FOOTER_DP);
        holdFailures = 0;
        holdLogged = false;
        EndPrefs.setLockedUntil(this, state.key, state.lockedUntil);

        log("END REACHED key=" + state.key + " at=" + state.position
                + (state.positionKnown
                    ? (state.positionInPixels ? " px" : " items")
                    : " (position unknown, using fence)"));
        showWall(state.key);
    }

    /** Manual reset from the settings screen: every feed starts over. */
    private void resetAllFeeds() {
        for (FeedState state : feeds.values()) {
            state.clearLock();
            state.positionKnown = false;
            EndPrefs.clearLock(this, state.key);
        }
        hideWall();
        Diag.lastProgress = 0f;
        log("RESET all feeds");
    }

    private void unlock(String key) {
        FeedState state = feeds.get(key);
        if (state != null) state.clearLock();
        EndPrefs.clearLock(this, key);
        hideWall();
        log("END CLEARED key=" + key);
    }

    // ------------------------------------------------------------------ fence

    /**
     * The fence is a transparent window over the feed band only. The app's own
     * top bar and bottom bar stay uncovered, so its navigation and the browser
     * address bar keep working natively, and so does Android's gesture bar.
     *
     * Inside the band the fence owns the touches:
     *  - a tap is handed to the app, at the exact spot, as a single tap;
     *  - a swipe down steps the feed back one page or one item;
     *  - a swipe up steps forward again, but never past the end;
     *  - beyond the end, a swipe up only pulls the footer and lets it spring.
     *
     * The feed stays visible underneath. Nothing covers the screen.
     */
    private void showWall(String key) {
        FeedState state = stateFor(key);
        boolean unitUsable = state.positionInPixels || state.profile == Feeds.PROFILE_ITEM;
        if (state.positionKnown && unitUsable && state.feedBounds != null) {
            showCurtain(state);
        } else {
            showFence(key);
        }
    }

    // ---------------------------------------------------------------- curtain

    private static final int FOOTER_DP = 58;
    private static final int LEAD_DP = 40;

    private View curtain;
    private View curtainSheet;
    private WindowManager.LayoutParams curtainParams;
    private int curtainHeight;
    private long lastBarMeasureAt;
    private int lastBarTop = -1;

    private void showCurtain(FeedState state) {
        if (windowManager == null) return;

        if (curtain != null && state.key.equals(wallKey)) {
            handler.removeCallbacks(tick);
            handler.post(tick);
            updateCurtain(state);
            return;
        }

        hideWall();
        wallKey = state.key;

        Rect area = curtainArea(state);
        curtainHeight = area.height();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);

        View line = new View(this);
        line.setBackgroundColor(ACID);
        sheet.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(20), dp(11), dp(20), dp(13));
        footer.setBackgroundColor(Color.rgb(5, 5, 5));

        wallHeadline = label(headlineFor(state.key), ACID, 11, Typeface.BOLD);
        wallHeadline.setLetterSpacing(.18f);
        footer.addView(wallHeadline);

        wallCountdown = label("--:--", MUTED, 12, Typeface.NORMAL);
        LinearLayout.LayoutParams countdownLp = wrap();
        countdownLp.topMargin = dp(3);
        footer.addView(wallCountdown, countdownLp);

        sheet.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(FOOTER_DP)));

        // Black below the footer. Tall enough to cover the whole feed when
        // the sheet is pushed all the way up; clipped by the window otherwise.
        View filler = new View(this);
        filler.setBackgroundColor(Color.rgb(5, 5, 5));
        sheet.addView(filler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, realScreenHeight()));

        root.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
        curtainSheet = sheet;
        wallColumn = sheet;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                area.width(),
                area.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = area.left;
        lp.y = area.top;

        try {
            windowManager.addView(root, lp);
            curtain = root;
            curtainParams = lp;
            Diag.wallUp = true;
            Diag.endMode = "curtain (" + (state.positionInPixels ? "px" : "items") + ")";
            updateCurtain(state);
            log("CURTAIN UP key=" + state.key + " area=" + area.toShortString()
                    + " unit=" + (state.positionInPixels ? "px" : "items"));
            handler.post(tick);
        } catch (Exception e) {
            log("CURTAIN FAILED key=" + state.key, e);
            curtain = null;
            curtainParams = null;
            curtainSheet = null;
            wallColumn = null;
            wallKey = null;
        }
    }

    /** Slide the sheet so its top edge sits at the end of the feed. */
    private void updateCurtain(FeedState state) {
        if (curtainSheet == null) return;

        float over = overshootPixels(state);
        // Events trail the frame a little; while moving forward, lead the
        // content so no strip of new items shows under the marker.
        float lead = state.lastMoveForward && over > -dp(8) ? dp(LEAD_DP) : 0f;
        float covered = Math.max(0f, over + lead);
        float rest = curtainHeight - dp(FOOTER_DP) - dp(2);
        float translation = Math.max(0f, rest - covered);
        curtainSheet.setTranslationY(translation);
    }

    private float overshootPixels(FeedState state) {
        float over = state.overshoot();
        return state.positionInPixels ? over : over * curtainHeight;
    }

    private void relayoutCurtain(FeedState state) {
        if (curtain == null || curtainParams == null) return;
        Rect area = curtainArea(state);
        if (area.height() == curtainParams.height && area.top == curtainParams.y
                && area.width() == curtainParams.width && area.left == curtainParams.x) return;
        curtainParams.x = area.left;
        curtainParams.y = area.top;
        curtainParams.width = area.width();
        curtainParams.height = area.height();
        curtainHeight = area.height();
        try {
            windowManager.updateViewLayout(curtain, curtainParams);
            updateCurtain(state);
            log("CURTAIN moved to " + area.toShortString());
        } catch (Exception e) {
            log("CURTAIN relayout failed", e);
        }
    }

    /**
     * The screen area the feed occupies: its own bounds, trimmed to the
     * screen, and trimmed above an app bar that sits over its bottom edge.
     */
    /**
     * The screen area the feed occupies, straight from the feed node's own
     * bounds. v17 also hunted for the app's bottom bar and trimmed above it,
     * which is what left a strip of feed showing under the end marker: the
     * hunt found the wrong edge. A feed's list already stops where the app's
     * bar begins, so its own bounds are the answer.
     */
    private Rect curtainArea(FeedState state) {
        Rect feed = state.feedBounds;
        int screenBottom = realScreenHeight() - navigationBarHeight();

        Rect area = new Rect();
        area.left = Math.max(0, feed.left);
        area.right = Math.min(screenWidth, feed.right);
        area.top = Math.max(statusBarHeight(), feed.top);
        area.bottom = Math.min(feed.bottom, screenBottom);
        if (area.bottom - area.top < dp(120)) area.bottom = area.top + dp(120);
        return area;
    }

    // ------------------------------------------------------------------ fence

    /**
     * Fallback for a feed that reports no position at all: a fence that owns
     * the touches, steps a page on a swipe and hands taps through.
     */
    private void showFence(String key) {
        if (windowManager == null) return;

        if (wall != null && key.equals(wallKey)) {
            handler.removeCallbacks(tick);
            handler.post(tick);
            return;
        }

        hideWall();
        wallKey = key;
        stepsBack = 0;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClickable(true);

        // Footer along the bottom edge of the band: the end of the list.
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(20), dp(11), dp(20), dp(13));
        footer.setBackgroundColor(Color.rgb(5, 5, 5));

        wallHeadline = label(headlineFor(key), ACID, 11, Typeface.BOLD);
        wallHeadline.setLetterSpacing(.18f);
        footer.addView(wallHeadline);

        wallCountdown = label("--:--", MUTED, 12, Typeface.NORMAL);
        LinearLayout.LayoutParams countdownLp = wrap();
        countdownLp.topMargin = dp(3);
        footer.addView(wallCountdown, countdownLp);

        View line = new View(this);
        line.setBackgroundColor(ACID);

        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
        stack.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(stack, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        wallColumn = stack;

        root.setOnTouchListener(new FenceTouch());

        int screenBottom = realScreenHeight() - navigationBarHeight();
        int top;
        int height;
        FeedState state = feeds.get(key);
        Rect feed = state != null ? state.feedBounds : null;

        if (feed != null && feed.height() >= dp(120)) {
            Rect area = curtainArea(state);
            top = area.top;
            height = area.height();
        } else {
            top = statusBarHeight() + dp(TOP_STRIP_DP);
            int bottom = navigationBarHeight() + dp(BOTTOM_STRIP_DP);
            height = Math.max(dp(120), realScreenHeight() - top - bottom);
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = top;

        try {
            windowManager.addView(root, lp);
            wall = root;
            wallParams = lp;
            Diag.wallUp = true;
            Diag.endMode = "fence (no position)";
            log("FENCE UP (no position from this feed) key=" + key + " band=" + top + ".." + (top + height));
            handler.post(tick);
        } catch (Exception e) {
            log("FENCE FAILED key=" + key, e);
            wall = null;
            wallParams = null;
            wallColumn = null;
            wallKey = null;
        }
    }

    private static final int TOP_STRIP_DP = 64;
    private static final int BOTTOM_STRIP_DP = 60;
    private static final long TAP_MS = 60L;
    private static final long PASS_THROUGH_SETTLE_MS = 40L;
    private static final long PASS_THROUGH_TIMEOUT_MS = 450L;

    private WindowManager.LayoutParams wallParams;
    private boolean passingThrough;
    private int stepsBack;
    private final Runnable restoreTouchable = this::restoreTouchable;

    /** Owns every touch inside the band. */
    private final class FenceTouch implements View.OnTouchListener {
        private float downX;
        private float downY;
        private long downAt;
        private boolean moved;
        private boolean handled;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    downAt = event.getEventTime();
                    moved = false;
                    handled = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!moved && (Math.abs(dx) > dp(10) || Math.abs(dy) > dp(10))) moved = true;
                    if (!moved || handled) return true;

                    if (Math.abs(dy) >= Math.abs(dx)) {
                        if (dy < 0 && stepsBack == 0) {
                            // Past the end: only the footer gives.
                            float pull = Math.max(-dp(28), dy * 0.18f);
                            if (wallColumn != null) wallColumn.setTranslationY(pull);
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (wallColumn != null) {
                        wallColumn.animate().translationY(0f).setDuration(160).start();
                    }

                    if (!moved) {
                        if (event.getEventTime() - downAt < 400L) passTap(downX, downY);
                        return true;
                    }

                    if (Math.abs(dy) < Math.abs(dx)) return true;

                    if (dy > dp(40)) {
                        stepFeed(false, downX, downY);
                    } else if (dy < -dp(40)) {
                        if (stepsBack > 0) {
                            stepFeed(true, downX, downY);
                        } else {
                            pulseFooter();
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
                    if (wallColumn != null) {
                        wallColumn.animate().translationY(0f).setDuration(160).start();
                    }
                    return true;

                default:
                    return true;
            }
        }
    }

    /**
     * Hand a tap to the app. The fence is made untouchable for a moment, the
     * tap is dispatched at the exact spot the user touched, and the fence
     * becomes touchable again when Android reports the tap done.
     */
    private void passTap(float x, float y) {
        if (wall == null || wallParams == null || passingThrough) return;

        passingThrough = true;
        wallParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            windowManager.updateViewLayout(wall, wallParams);
        } catch (Exception e) {
            passingThrough = false;
            return;
        }

        handler.removeCallbacks(restoreTouchable);
        handler.postDelayed(restoreTouchable, PASS_THROUGH_TIMEOUT_MS);

        handler.postDelayed(() -> {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription tap;
            try {
                tap = new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, TAP_MS))
                        .build();
            } catch (Exception e) {
                restoreTouchable();
                return;
            }

            boolean accepted = dispatchGesture(tap, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { restoreTouchable(); }
                @Override public void onCancelled(GestureDescription d) { restoreTouchable(); }
            }, handler);
            if (!accepted) restoreTouchable();
        }, PASS_THROUGH_SETTLE_MS);
    }

    private void restoreTouchable() {
        handler.removeCallbacks(restoreTouchable);
        passingThrough = false;
        if (wall == null || wallParams == null) return;
        if ((wallParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) return;
        wallParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            windowManager.updateViewLayout(wall, wallParams);
        } catch (Exception ignored) {
            // The fence is gone.
        }
    }

    /**
     * Move the feed one page, or one item, in the direction the user swiped.
     * Forward steps are only granted back what backward steps took, so the
     * end stays where it was.
     */
    private void stepFeed(boolean forward, float x, float y) {
        FeedState state = wallKey == null ? null : feeds.get(wallKey);
        AccessibilityNodeInfo target = null;
        AccessibilityNodeInfo root = null;

        try {
            root = getRootInActiveWindow();
            target = feedNode(state, root, x, y);
            if (target == null) {
                log("STEP no scrollable found key=" + wallKey);
                pulseFooter();
                return;
            }

            boolean done = target.performAction(forward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            if (!done && Build.VERSION.SDK_INT >= 29) {
                done = target.performAction((forward
                        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP).getId());
            }
            log("STEP " + (forward ? "forward" : "back") + " ok=" + done);
            if (!done) {
                pulseFooter();
                return;
            }

            if (state != null && state.positionKnown && !forward) {
                // The feed's own scroll event will confirm we are above the
                // end and take the fence down; until then, assume it.
                state.atEnd = false;
                hideWall();
                log("ABOVE end after step, fence down, scrolling is native now");
            } else {
                stepsBack += forward ? -1 : 1;
            }
        } catch (Exception e) {
            log("STEP failed key=" + wallKey, e);
        } finally {
            if (target != null && target != root) target.recycle();
            if (root != null) root.recycle();
        }
    }

    /**
     * The node that carries the feed: by the id learned from its scroll
     * events, else the scrollable under the finger, else the largest
     * scrollable on screen.
     */
    private AccessibilityNodeInfo feedNode(FeedState state, AccessibilityNodeInfo root, float x, float y) {
        if (root == null) return null;

        if (state != null && state.feedViewId != null) {
            try {
                List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(state.feedViewId);
                if (byId != null) {
                    AccessibilityNodeInfo pick = null;
                    for (AccessibilityNodeInfo node : byId) {
                        if (node == null) continue;
                        if (pick == null && node.isScrollable()) pick = node; else node.recycle();
                    }
                    if (pick != null) return pick;
                }
            } catch (Exception ignored) {
                // Fall through.
            }
        }

        if (x >= 0f && y >= 0f) {
            AccessibilityNodeInfo under = findScrollableAt(root, x, y);
            if (under != null) return under;
        }

        return largestScrollable(root);
    }

    private AccessibilityNodeInfo largestScrollable(AccessibilityNodeInfo root) {
        Rect bounds = new Rect();
        AccessibilityNodeInfo best = null;
        int bestArea = 0;

        java.util.ArrayDeque<AccessibilityNodeInfo> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        int visited = 0;

        while (!stack.isEmpty() && visited++ < 400) {
            AccessibilityNodeInfo node = stack.pop();
            if (node == null) continue;

            boolean keep = false;
            if (node.isScrollable()) {
                node.getBoundsInScreen(bounds);
                int area = bounds.width() * bounds.height();
                if (area > bestArea) {
                    if (best != null && best != root) best.recycle();
                    best = node;
                    bestArea = area;
                    keep = true;
                }
            }

            int count = node.getChildCount();
            for (int i = count - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.push(child);
            }

            if (!keep && node != root) node.recycle();
        }
        return best;
    }

    /**
     * Deepest scrollable node whose bounds contain the touch point, preferring
     * one that scrolls vertically. Falls back to the first scrollable found.
     */
    private AccessibilityNodeInfo findScrollableAt(AccessibilityNodeInfo root, float x, float y) {
        if (root == null) return null;
        Rect bounds = new Rect();
        AccessibilityNodeInfo best = null;
        AccessibilityNodeInfo first = null;

        java.util.ArrayDeque<AccessibilityNodeInfo> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        int visited = 0;

        while (!stack.isEmpty() && visited++ < 400) {
            AccessibilityNodeInfo node = stack.pop();
            if (node == null) continue;

            boolean keep = false;
            if (node.isScrollable()) {
                if (first == null) {
                    first = node;
                    keep = true;
                }
                node.getBoundsInScreen(bounds);
                if (bounds.contains((int) x, (int) y)) {
                    if (best != null && best != first && best != root) best.recycle();
                    best = node;
                    keep = true;
                }
            }

            int count = node.getChildCount();
            for (int i = count - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.push(child);
            }

            if (!keep && node != root) node.recycle();
        }

        if (best != null) {
            if (first != null && first != best && first != root) first.recycle();
            return best;
        }
        return first;
    }

    private String headlineFor(String key) {
        return "END OF SCROLLING";
    }

    private void pulseFooter() {
        if (curtain != null) return;   // the curtain's sheet is positioned by scroll, never pulsed
        if (wallColumn == null || wallHeadline == null) return;

        final String original = wallHeadline.getText().toString();
        wallHeadline.setText("NOTHING BELOW THIS");

        wallColumn.animate()
                .translationY(-dp(10))
                .setDuration(70)
                .withEndAction(() -> wallColumn.animate().translationY(0f).setDuration(180).start())
                .start();

        handler.postDelayed(() -> {
            if (wallHeadline != null) wallHeadline.setText(original);
        }, 900L);
    }

    private void hideWall() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(rescue);
        handler.removeCallbacks(restoreTouchable);
        passingThrough = false;

        if (wall != null && windowManager != null) {
            try {
                windowManager.removeView(wall);
            } catch (Exception ignored) {
                // The window was already gone.
            }
        }
        if (curtain != null && windowManager != null) {
            try {
                windowManager.removeView(curtain);
            } catch (Exception ignored) {
                // The window was already gone.
            }
        }
        curtain = null;
        curtainParams = null;
        curtainSheet = null;

        wall = null;
        wallParams = null;
        wallColumn = null;
        wallHeadline = null;
        wallCountdown = null;
        wallKey = null;
        Diag.wallUp = false;
        Diag.endMode = "down";
    }

    private int statusBarHeight() {
        if (Build.VERSION.SDK_INT >= 30 && windowManager != null) {
            try {
                android.view.WindowInsets insets = windowManager.getCurrentWindowMetrics().getWindowInsets();
                return insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
            } catch (Exception ignored) {
                // Fall through to the resource.
            }
        }
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(28);
    }

    /**
     * Height of the system navigation area at the bottom. The dimen resource
     * reports the three-button height even on gesture navigation, which left
     * a strip of content visible under the footer; the window metrics do not.
     */
    private int navigationBarHeight() {
        if (Build.VERSION.SDK_INT >= 30 && windowManager != null) {
            try {
                android.view.WindowInsets insets = windowManager.getCurrentWindowMetrics().getWindowInsets();
                return insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
            } catch (Exception ignored) {
                // Fall through to the resource.
            }
        }
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(48);
    }

    private int realScreenHeight() {
        try {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            if (metrics.heightPixels > 0) return metrics.heightPixels;
        } catch (Exception ignored) {
            // Fall through to the app-visible height.
        }
        return screenHeight;
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

    private static void log(String message) {
        Log.i(TAG, message);
        Diag.log(message);
    }

    private static void log(String message, Throwable error) {
        Log.w(TAG, message, error);
        Diag.log(message + ": " + error);
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
        Diag.connected = false;
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
