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
 * Not a blocker. When a feed reaches its limit an end marker is pinned to the
 * exact pixel where the limit fell. Everything past it is covered, and a list
 * pushed past it is brought straight back, the way a web page comes back from
 * an overscroll. The feed stays visible, scrolling back and tapping stay the
 * app's own, the app's bars and Android's navigation stay untouched. Only
 * going further is impossible.
 *
 * Counting follows the scroll events of the list being scrolled, in pixels,
 * measured against that list's own height. For a feed that never reports
 * scrolling a burst of content changes counts instead. Both are gated on a
 * touch having just happened, which a 1 px sensor window reports without ever
 * seeing where. See docs/measurements.md for what feeds really report.
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
            relook = true;
            paneChangedAt = now;
            onWindowAnnounced(pkg, now);
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
            expireStaleProgress(state, now);
            int result = trackScroll(state, event, now, false);
            if ((result & FeedState.MOVED) != 0) reportProgress(state);
            if ((result & FeedState.LIMIT_REACHED) != 0) lock(state, now, true);
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (Feeds.isBrowser(pkg)) pollBrowserHost(pkg, now);
            if (!touchedWithin(TOUCH_GATE_BURST_MS, now)) return;
            if (state.measureContentBurst(now) > 0f) creditBurstLater(state);
        }
    }

    // ------------------------------------------------------------- which list

    /** Scroll events closer together than this belong to one movement. */
    private static final long GESTURE_GAP_MS = 350L;
    private static final long SAME_MOVEMENT_MS = 1500L;
    private static final long SHADOW_TRUST_MS = 5000L;

    /** A pane changed inside the app: the next scroll event looks at its node again. */
    private boolean relook;
    private long paneChangedAt;

    /**
     * How long after a pane change a list's movement is taken to be the app
     * putting the list back in place, rather than the user scrolling it.
     */
    private static final long PANE_SETTLE_MS = 700L;

    /**
     * Feed one scroll event to the state and return its result bits.
     *
     * The node behind an event is fetched once per movement, never per event:
     * every fetch is a synchronous round trip into the app's own UI thread,
     * and doing that per scroll frame is what makes an app feel less smooth.
     * Inside a movement the event's class name is identity enough.
     */
    private int trackScroll(FeedState state, AccessibilityEvent event, long now, boolean locked) {
        int dy = event.getScrollDeltaY();
        int dx = event.getScrollDeltaX();
        int scrollY = event.getScrollY();

        // Horizontal shelves and layout passes report a delta of zero. They
        // say nothing about the feed and must never be mistaken for it.
        boolean deltaSet = !(dy == -1 && dx == -1);
        if (deltaSet && dy == 0 && scrollY <= 0) return 0;

        CharSequence rawClass = event.getClassName();
        String cls = rawClass == null ? "" : rawClass.toString();

        FeedState.ListTrack list = state.list;
        if (state.isShadow(cls)) {
            if (list != null && now - list.lastEventAt < SHADOW_TRUST_MS) return 0;
            state.unshadow(cls);
        }

        boolean sameMovement = !relook && list != null && cls.equals(list.className)
                && now - list.lastEventAt < GESTURE_GAP_MS;
        boolean arrived = relook && now - paneChangedAt < PANE_SETTLE_MS;
        if (!sameMovement && !identify(state, event, cls, now, arrived)) return 0;
        relook = false;

        boolean hadSignal = state.scrollSeen;
        int result = state.observe(dy, dx, scrollY,
                touchedWithin(TOUCH_GATE_SCROLL_MS, now), locked, config.screens, now);
        if (!hadSignal && state.scrollSeen) EndPrefs.setScrollSignal(this, state.key, true);
        return result;
    }

    /**
     * Look at the node behind a scroll event and decide which list it is.
     * False when the event is not from a list worth following.
     */
    private boolean identify(FeedState state, AccessibilityEvent event, String cls, long now,
                             boolean paneChanged) {
        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
            if (source == null) return false;

            Rect bounds = new Rect();
            source.getBoundsInScreen(bounds);
            if (bounds.height() < dp(120) || bounds.width() < screenWidth / 2) return false;

            // Stable for the life of the view: built from the view's own
            // accessibility id and its window.
            int id = source.hashCode();
            boolean scrollable = source.isScrollable();
            FeedState.ListTrack current = state.list;

            if (current != null && current.id != id && !cls.equals(current.className)
                    && now - current.lastEventAt < SAME_MOVEMENT_MS
                    && covers(current, bounds)) {
                // Two nodes on top of each other report one movement. Brave
                // does this: the page, and a frame around it. Follow the one
                // that can actually scroll and ignore the other from now on.
                if (current.scrollable && !scrollable) {
                    state.shadow(cls);
                    log("FEED LIST ignores " + cls + ", it repeats " + current.className);
                    return false;
                }
                if (!current.scrollable && scrollable) {
                    state.shadow(current.className);
                    log("FEED LIST ignores " + current.className + ", it repeats " + cls);
                }
            }

            boolean switched = current == null || current.id != id;
            boolean resized = !switched && (current.top != bounds.top || current.bottom != bounds.bottom
                    || current.left != bounds.left || current.right != bounds.right);

            FeedState.ListTrack list = state.useList(id, cls, source.getViewIdResourceName(), scrollable,
                    bounds.left, bounds.top, bounds.right, bounds.bottom, paneChanged);

            boolean wayOut = switched && state.claimExit(now);
            if (switched) {
                log("FEED LIST " + list.viewId + " " + bounds.toShortString() + " class=" + cls
                        + (scrollable ? "" : " (pager, no scroll actions)")
                        + (wayOut ? ", the way out: no end here" : ""));
            }
            if ((switched || resized) && curtain != null && state.key.equals(wallKey)) {
                relayoutCurtain(state, false);
                updateCurtain(state);
            }
            return true;
        } catch (Exception e) {
            return false;   // the node went away
        } finally {
            if (source != null) source.recycle();
        }
    }

    /** True when the two areas are largely the same patch of screen. */
    private static boolean covers(FeedState.ListTrack list, Rect other) {
        int left = Math.max(list.left, other.left);
        int top = Math.max(list.top, other.top);
        int right = Math.min(list.right, other.right);
        int bottom = Math.min(list.bottom, other.bottom);
        if (right <= left || bottom <= top) return false;

        long shared = (long) (right - left) * (bottom - top);
        long a = (long) (list.right - list.left) * (list.bottom - list.top);
        long b = (long) other.width() * other.height();
        return shared * 10 >= Math.min(a, b) * 8;
    }

    // ------------------------------------------------------------ past the end

    private static final int BRAKE_DP = 3;
    private static final int REST_DP = 8;
    private static final long SPRING_HOLD_MS = 160L;
    private static final long SETTLE_MS = 200L;
    private static final int SPRING_MAX_TRIES = 4;
    private static final int SPRING_MAX_PER_WINDOW = 10;
    private static final long SPRING_WINDOW_MS = 10_000L;

    private boolean springing;
    private long springEndedAt;
    private int springTries;
    private long springWindowStart;
    private int springsInWindow;

    /**
     * An ended feed gets a curtain: a window that takes no touches, opaque
     * from the end of the feed downward, that follows the feed's own scroll
     * events. Scrolling back and tapping stay the app's own.
     *
     * The curtain hides what lies past the end but cannot stop the list. So
     * forward movement past the end is answered the way a web page answers an
     * overscroll: the list is caught and brought back to its end, by exactly
     * the distance it went over. Backward movement is never answered.
     */
    private void onScrolledWhileEnded(FeedState state, AccessibilityEvent event, long now) {
        int result = trackScroll(state, event, now, true);
        if (wall != null) return;   // a fence owns this feed
        if (curtain == null || !state.key.equals(wallKey)) showWall(state.key);
        if ((result & FeedState.MOVED) != 0) afterEndedMove(state);
    }

    private void afterEndedMove(FeedState state) {
        if (curtain == null) return;
        updateCurtain(state);

        FeedState.ListTrack list = state.list;
        if (list != null && list.lastMoveForward && !springing) {
            springTries = 0;   // the user pushed again
            if (state.overshoot() > dp(BRAKE_DP)) spring(state, "brake");
        }

        handler.removeCallbacks(settle);
        handler.postDelayed(settle, SETTLE_MS);
    }

    /** Runs when the list has stopped moving. */
    private final Runnable settle = new Runnable() {
        @Override public void run() {
            FeedState state = wallKey == null ? null : feeds.get(wallKey);
            if (state == null || curtain == null || springing) return;

            if (state.overshoot() > dp(REST_DP)) {
                if (!pageBack(state)) spring(state, "rest");
                return;
            }

            springTries = 0;
            if (state.list != null) state.list.lastMoveForward = false;
            refreshListBounds(state);
            relayoutCurtain(state, true);
            updateCurtain(state);
        }
    };

    private int pagesInWindow;
    private long pageWindowStart;

    /**
     * A hard fling in a browser travels several screens before the first
     * scroll event about it arrives, and one drag covers less than a screen.
     * So while the list rests more than a screen past its end, ask its own
     * node to page back; the drag then only has to finish the job exactly.
     * True when a page was asked for.
     */
    private boolean pageBack(FeedState state) {
        FeedState.ListTrack list = state.list;
        if (list == null || !list.scrollable || springing) return false;
        if (state.overshoot() < list.height * 1.05f) return false;

        long now = android.os.SystemClock.uptimeMillis();
        if (now - pageWindowStart > SPRING_WINDOW_MS) {
            pageWindowStart = now;
            pagesInWindow = 0;
        }
        if (pagesInWindow >= 20) return false;
        pagesInWindow++;

        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo node = null;
        boolean done = false;
        try {
            root = getRootInActiveWindow();
            node = findList(root, list);
            if (node != null) done = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        } catch (Exception ignored) {
            // Fall back to the drag.
        } finally {
            if (node != null && node != root) node.recycle();
            if (root != null) root.recycle();
        }

        if (done) {
            log("PAGE back, over=" + Math.round(state.overshoot()) + "px");
            handler.removeCallbacks(settle);
            handler.postDelayed(settle, 320L);
        }
        return done;
    }

    /**
     * Bring the list back to its end, the way a web page comes back from an
     * overscroll, with one injected touch:
     *
     *  - it goes down and spends the touch slop, slowly. Going down alone stops
     *    a fling, and while the slop is spent the scroll events that were still
     *    on their way arrive, so the overshoot is known exactly instead of a
     *    tenth of a second late. A touch that merely holds still does not do:
     *    measured, Android reports such a stroke complete at once;
     *  - it drags the list back by exactly that distance;
     *  - it ends on a crawl, so the release has no velocity and nothing flings.
     *
     * At rest nothing is in flight and the hold is skipped. If a finger is on
     * the screen Android cancels the injected touch, and the next rest tries
     * again.
     */
    private void spring(final FeedState state, final String why) {
        if (springing || curtainParams == null) return;

        long now = android.os.SystemClock.uptimeMillis();
        if (now - springEndedAt < 120L) return;

        if (now - springWindowStart > SPRING_WINDOW_MS) {
            springWindowStart = now;
            springsInWindow = 0;
        }
        if (springTries >= SPRING_MAX_TRIES || springsInWindow >= SPRING_MAX_PER_WINDOW) {
            if (springTries == SPRING_MAX_TRIES || springsInWindow == SPRING_MAX_PER_WINDOW) {
                log("SPRING gives up at overshoot " + Math.round(state.overshoot()) + "px");
            }
            springTries++;
            springsInWindow++;
            return;
        }
        if (state.overshoot() <= dp(REST_DP)) return;

        // Start well inside the list: its top strip may lie under an app bar
        // that takes the drag for itself. Alternate the column, so a try that
        // landed on something that swallowed it is not repeated in place.
        float column = (springTries % 2 == 0) ? 0.5f : 0.28f;
        final float x = curtainParams.x + curtainParams.width * column;
        int barBottom = state.list == null ? curtainParams.y
                : Math.max(curtainParams.y, state.list.restTop);
        // Below the app's own bar, and below a web page's sticky header. And
        // never on END's own marker, which swallows every touch; the black
        // under it lets them through.
        float wanted = Math.max(barBottom + dp(48), curtainParams.y + curtainHeight * 0.18f);
        if (guardTop != Integer.MIN_VALUE && wanted > guardTop - dp(24)
                && wanted < guardTop + guardHeight + dp(24)) {
            wanted = guardTop + guardHeight + dp(32);
        }
        final float y0 = wanted;

        springing = true;
        springTries++;
        springsInWindow++;
        handler.removeCallbacks(springTimeout);
        handler.postDelayed(springTimeout, 1600L);

        if (!"brake".equals(why)) {
            dragBack(state, why, null, x, y0);
            return;
        }

        try {
            final float slopEnd = y0 + touchSlop();
            Path slopPath = new Path();
            slopPath.moveTo(x, y0);
            slopPath.lineTo(x, slopEnd);
            final GestureDescription.StrokeDescription hold =
                    new GestureDescription.StrokeDescription(slopPath, 0, SPRING_HOLD_MS, true);
            boolean accepted = dispatchGesture(new GestureDescription.Builder().addStroke(hold).build(),
                    new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription d) {
                            dragBack(state, why, hold, x, slopEnd);
                        }
                        @Override public void onCancelled(GestureDescription d) {
                            springDone("brake cancelled, a finger is on the screen");
                        }
                    }, handler);
            if (!accepted) springDone("brake refused");
        } catch (Exception e) {
            log("SPRING failed", e);
            springDone("error");
        }
    }

    /** The drag itself, continuing the held touch when there is one. */
    private void dragBack(FeedState state, String why,
                          GestureDescription.StrokeDescription held, float x, float y0) {
        if (curtainParams == null) {
            springDone("the end went down");
            return;
        }

        float over = state.overshoot();

        // A drag only moves the list after the touch slop has been spent.
        // A held touch has spent it already.
        int slop = held == null ? touchSlop() : 0;

        // Measured in YouTube: with its top bar scrolled out of view, a drag
        // downward first pulls that bar back in, and only what is left of the
        // drag moves the list. 302 px of drag moved the list 39 px. The bar
        // took 320 px where its height is 270, so allow for the full distance
        // the list's top edge has to come down; too far back is harmless, too
        // little means a second drag.
        refreshListBounds(state);
        FeedState.ListTrack list = state.list;
        int hiddenBar = list == null ? 0 : Math.max(0, list.restTop - list.top);

        float floor = Math.min(curtainParams.y + curtainHeight,
                realScreenHeight() - navigationBarHeight()) - dp(28);
        float distance = over <= dp(REST_DP)
                ? slop + dp(2)   // nothing left to correct; still end as a drag, never as a tap
                : Math.min(over + slop + hiddenBar, floor - y0);
        if (distance < dp(4)) distance = dp(4);

        float y1 = y0 + distance;
        long duration = Math.max(110L, Math.min(480L, (long) (distance / 3.2f)));

        try {
            Path drag = new Path();
            drag.moveTo(x, y0);
            drag.lineTo(x, y1);
            GestureDescription.StrokeDescription main = held == null
                    ? new GestureDescription.StrokeDescription(drag, 0, duration, true)
                    : held.continueStroke(drag, 0, duration, true);

            Path crawl = new Path();
            crawl.moveTo(x, y1);
            crawl.lineTo(x, y1 + 2f);
            final GestureDescription.StrokeDescription last =
                    main.continueStroke(crawl, 0, 110L, false);

            boolean accepted = dispatchGesture(new GestureDescription.Builder().addStroke(main).build(),
                    new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription d) {
                            boolean sent = false;
                            try {
                                sent = dispatchGesture(new GestureDescription.Builder()
                                        .addStroke(last).build(), new GestureResultCallback() {
                                    @Override public void onCompleted(GestureDescription g) { springDone("done"); }
                                    @Override public void onCancelled(GestureDescription g) { springDone("crawl cancelled"); }
                                }, handler);
                            } catch (Exception e) {
                                log("SPRING crawl failed", e);
                            }
                            if (!sent) springDone("no crawl");
                        }
                        @Override public void onCancelled(GestureDescription d) {
                            springDone("cancelled, a finger is on the screen");
                        }
                    }, handler);

            log("SPRING " + why + " over=" + Math.round(over) + "px drag=" + Math.round(distance)
                    + "px" + (hiddenBar > 0 ? " (" + hiddenBar + " for the app bar)" : "")
                    + " from " + Math.round(x) + "," + Math.round(y0)
                    + " in " + duration + "ms" + (accepted ? "" : " REFUSED"));
            if (!accepted) springDone("refused");
        } catch (Exception e) {
            log("SPRING failed", e);
            springDone("error");
        }
    }

    /**
     * The app's touch slop is not ours to read. Twice our own was measured to
     * land on the end; going a little too far back is harmless.
     */
    private int touchSlop() {
        return 2 * android.view.ViewConfiguration.get(this).getScaledTouchSlop();
    }

    private final Runnable springTimeout = new Runnable() {
        @Override public void run() {
            if (springing) springDone("timed out");
        }
    };

    private void springDone(String how) {
        handler.removeCallbacks(springTimeout);
        springing = false;
        springEndedAt = android.os.SystemClock.uptimeMillis();
        if (!"done".equals(how)) log("SPRING " + how);
        handler.removeCallbacks(settle);
        handler.postDelayed(settle, SETTLE_MS + 60L);
    }

    /** At rest, read the list's bounds again: app bars come and go while scrolling. */
    private void refreshListBounds(FeedState state) {
        FeedState.ListTrack list = state.list;
        if (list == null) return;

        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo node = null;
        try {
            root = getRootInActiveWindow();
            node = findList(root, list);
            if (node == null) return;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.height() < dp(120)) return;
            list.left = bounds.left;
            list.top = bounds.top;
            list.right = bounds.right;
            list.bottom = bounds.bottom;
            list.restTop = Math.max(list.restTop, bounds.top);
        } catch (Exception ignored) {
            // Keep what we had.
        } finally {
            if (node != null && node != root) node.recycle();
            if (root != null) root.recycle();
        }
    }

    /** The node of a tracked list, by view id when it has one, else by a shallow search. */
    private AccessibilityNodeInfo findList(AccessibilityNodeInfo root, FeedState.ListTrack list) {
        if (root == null) return null;

        if (list.viewId != null) {
            List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(list.viewId);
            AccessibilityNodeInfo pick = null;
            if (byId != null) {
                for (AccessibilityNodeInfo candidate : byId) {
                    if (candidate == null) continue;
                    if (pick == null && candidate.hashCode() == list.id) pick = candidate;
                    else candidate.recycle();
                }
            }
            return pick;
        }

        java.util.ArrayDeque<AccessibilityNodeInfo> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        int visited = 0;
        AccessibilityNodeInfo found = null;
        while (!stack.isEmpty() && visited++ < 200) {
            AccessibilityNodeInfo node = stack.pop();
            if (node == null) continue;
            if (found == null && node.hashCode() == list.id) {
                found = node;
                break;
            }
            int count = node.getChildCount();
            for (int i = count - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.push(child);
            }
            if (node != root) node.recycle();
        }
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo left = stack.pop();
            if (left != null && left != root && left != found) left.recycle();
        }
        return found;
    }

    // --------------------------------------------------------------- progress

    private float loggedProgress = -1f;

    /** One log line per quarter screen, not one per scroll event. */
    private void reportProgress(FeedState state) {
        Diag.lastProgress = state.progress;
        if (Math.abs(state.progress - loggedProgress) < 0.25f) return;
        loggedProgress = state.progress;
        log("PROGRESS key=" + state.key + " " + String.format("%.2f", state.progress)
                + " / " + config.screens
                + (state.list == null ? "" : " from " + state.list.unit()));
    }

    /**
     * A burst of content changes counts as one screen only if no scroll event
     * follows it. Content changes arrive at once and scroll events a tenth of
     * a second later, so crediting the burst right away counted the first
     * swipe of a feed twice.
     */
    private void creditBurstLater(final FeedState state) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (state.scrollSeen) return;
                long now = System.currentTimeMillis();
                if (state.isLocked(now)) return;

                Diag.burstCounts++;
                Diag.lastFeedEventAt = now;
                expireStaleProgress(state, now);
                state.progress += 1f;
                state.lastActivityAt = now;
                Diag.lastProgress = state.progress;
                log("PROGRESS key=" + state.key + " " + state.progress + " / " + config.screens
                        + " from a content burst, this feed reports no scrolling");
                if (state.progress >= config.screens) lock(state, now, false);
            }
        }, 450L);
    }

    // ------------------------------------------------------------- foreground

    /** The host last read from each browser, so a flicker of focus does not drop the end. */
    private final Map<String, String> lastHostByBrowser = new HashMap<>();

    /**
     * A package announced a window. That is not yet a foreground change:
     * bubbles, dictation overlays, edge panels and toasts announce windows
     * without ever coming to the front, and each of them used to take the end
     * off the screen for a moment. Only the package that owns the active
     * window is the foreground.
     */
    private void onWindowAnnounced(String pkg, long now) {
        if (Feeds.SYSTEM_UI.equals(pkg) || isTransientWindow(pkg)) return;

        if (pkg.equals(foregroundPackage) || pkg.equals(activeWindowPackage())) {
            onForegroundChanged(pkg, now);
            return;
        }

        // Not in front, or not yet: a launching app announces its window a
        // moment before that window becomes the active one. Look again.
        handler.removeCallbacks(foregroundCheck);
        handler.postDelayed(foregroundCheck, 350L);
    }

    private final Runnable foregroundCheck = new Runnable() {
        @Override public void run() {
            String active = activeWindowPackage();
            if (active == null || active.equals(foregroundPackage)) return;
            if (active.equals(getPackageName())) return;   // our own windows never count
            onForegroundChanged(active, System.currentTimeMillis());
        }
    };

    private void onForegroundChanged(String pkg, long now) {
        // The shade and system dialogs draw above the wall on their own, and
        // recents is the launcher, which is a real foreground change. So
        // System UI is never a reason to touch the wall.
        if (Feeds.SYSTEM_UI.equals(pkg)) return;

        // Keyboards, permission dialogs and edge panels announce a window
        // state change too, but the app underneath is still the foreground.
        if (isTransientWindow(pkg)) return;

        boolean changed = !pkg.equals(foregroundPackage);
        if (changed) log("FOREGROUND " + pkg + " (was " + foregroundPackage + ")");
        foregroundPackage = pkg;
        Diag.foreground = pkg;

        if (changed) {
            // Until the address bar can be read again, assume the browser
            // still shows the site it showed last.
            activeHost = lastHostByBrowser.get(pkg);
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
        if (host != null) {
            activeHost = Feeds.matchEnabledHost(host, config.hosts);
            if (activeHost == null) lastHostByBrowser.remove(pkg);
            else lastHostByBrowser.put(pkg, activeHost);
        }
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
            Diag.lastFeed = activeKey;
            Diag.lastProgress = state.progress;
            log("FEED key=" + activeKey
                    + " progress=" + state.progress
                    + " locked=" + state.isLocked(now));
        }

        if (activeKey == null || !activeKey.equals(wallKey)) {
            if (wallKey != null) log("END DOWN because the feed in front is now " + activeKey);
            hideWall();
        }
        if (activeKey != null && stateFor(activeKey).isLocked(now)) showWall(activeKey);
    }

    private FeedState stateFor(String key) {
        FeedState state = feeds.get(key);
        if (state != null) return state;

        state = new FeedState(key);
        state.lockedUntil = EndPrefs.lockedUntil(this, key);
        state.scrollSeen = EndPrefs.hasScrollSignal(this, key);
        feeds.put(key, state);
        return state;
    }

    private void expireStaleProgress(FeedState state, long now) {
        if (state.progress <= 0f || state.lastActivityAt <= 0L) return;
        if (now - state.lastActivityAt >= config.cooldownMillis()) state.reset();
    }

    /**
     * The limit is reached. When the list's own travel reached it, the state
     * has already pinned the end at the exact pixel; otherwise it is pinned
     * where the list stands now.
     */
    private void lock(FeedState state, long now, boolean pinnedByTravel) {
        state.progress = config.screens;
        state.lockedUntil = now + config.cooldownMillis();
        state.lastActivityAt = now;
        if (!pinnedByTravel) state.pinEndHere();
        if (!state.scrollSeen) EndPrefs.setScrollSignal(this, state.key, false);

        FeedState.ListTrack list = state.list;
        if (list != null && list.endPinned && list.scrollable) {
            // At rest the end line sits a marker's height above the list's
            // bottom edge. Let the list rest that much further on, so the line
            // lies exactly on the boundary between what was allowed and what
            // was not, and the marker covers only the latter.
            Rect listRect = listArea(state);
            list.endPosition += Math.max(0, Math.min(listRect.bottom, bottomEdge()) - restLine(listRect));
        }

        springTries = 0;
        loggedProgress = -1f;
        EndPrefs.setLockedUntil(this, state.key, state.lockedUntil);

        log("END REACHED key=" + state.key + (list == null
                ? " (this feed reports no scrolling, using fence)"
                : " list=" + list.viewId + " end=" + Math.round(list.endPosition)
                    + "px at=" + Math.round(list.position) + "px from " + list.unit()));
        showWall(state.key);
        afterEndedMove(state);
    }

    /** Manual reset from the settings screen: every feed starts over. */
    private void resetAllFeeds() {
        for (FeedState state : feeds.values()) {
            state.clearLock();
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
        // The fence takes the touches away from the app, and with them every
        // scroll event END could learn from. So it is only for a feed that is
        // known to report no scrolling at all.
        if (!state.scrollSeen && EndPrefs.knownSilent(this, key)) {
            showFence(key);
        } else {
            showCurtain(state);
        }
    }

    // ---------------------------------------------------------------- curtain

    private static final int FOOTER_DP = 58;
    private static final int LEAD_DP = 40;

    private View curtain;
    private View curtainSheet;
    private WindowManager.LayoutParams curtainParams;
    private int curtainHeight;

    /**
     * The marker is END's own and does nothing when touched. Without this a
     * tap on it would land on whatever the app has underneath, its navigation
     * bar for one, which nobody can see there.
     */
    private View markerGuard;
    private WindowManager.LayoutParams guardParams;

    /** The way out drawn on the marker, or null when this feed has none. */
    private View exitPill;
    private int guardTop = Integer.MIN_VALUE;
    private int guardHeight;

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
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.TOP);
        footer.setPadding(dp(20), dp(11), dp(14), dp(13) + handleInset());
        footer.setBackgroundColor(Color.rgb(5, 5, 5));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);

        wallHeadline = label(headlineFor(state.key), ACID, 11, Typeface.BOLD);
        wallHeadline.setLetterSpacing(.18f);
        copy.addView(wallHeadline);

        wallCountdown = label("--:--", MUTED, 12, Typeface.NORMAL);
        LinearLayout.LayoutParams countdownLp = wrap();
        countdownLp.topMargin = dp(3);
        copy.addView(wallCountdown, countdownLp);

        footer.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // The way out: one tap to the part of the same app that no algorithm
        // sorts. The user's own tap, and an ordinary link; END presses nothing
        // inside the app.
        exitPill = null;
        Feeds.Exit exit = state.key.indexOf('#') < 0 ? Feeds.exitFor(state.key) : null;
        if (exit != null) {
            TextView pill = label(exit.label + "  \u203a", ACID, 11, Typeface.BOLD);
            pill.setLetterSpacing(.12f);
            pill.setPadding(dp(12), dp(8), dp(12), dp(8));
            android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable();
            outline.setColor(Color.TRANSPARENT);
            outline.setStroke(dp(1), ACID);
            outline.setCornerRadius(dp(18));
            pill.setBackground(outline);
            LinearLayout.LayoutParams pillLp = wrap();
            pillLp.topMargin = dp(1);
            footer.addView(pill, pillLp);
            exitPill = pill;
        }

        sheet.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, markerHeight()));

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
            Diag.endMode = "curtain" + (state.list == null ? ", waiting for the list to move"
                    : " on " + state.list.unit());
            attachGuard();
            updateCurtain(state);
            log("CURTAIN UP key=" + state.key + " area=" + area.toShortString()
                    + " end line at " + restLine(listArea(state)));
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

    /**
     * Slide the sheet so its top edge sits on the end of the feed. The marker
     * belongs to the end, not to the screen: scroll back and it leaves through
     * the bottom edge like any footer, scroll down again and it returns.
     */
    private void updateCurtain(FeedState state) {
        if (curtainSheet == null || curtainParams == null) return;

        float over = state.signedOvershoot();
        FeedState.ListTrack list = state.list;
        // Events trail the frame a little; while moving forward past the end,
        // lead the content so no strip of new items shows under the marker.
        float lead = list != null && list.lastMoveForward && over > 0f ? dp(LEAD_DP) : 0f;
        float rest = restLine(listArea(state)) - curtainParams.y;
        float translation = Math.max(0f, Math.min(curtainHeight, rest - over - lead));
        // The way out has no end, so nothing of the end shows on it.
        if (list != null && list.free) translation = curtainHeight;
        curtainSheet.setTranslationY(translation);
        placeGuard(Math.round(translation));
    }

    /** Height of the marker under its acid line, in pixels. */
    private int markerHeight() {
        return dp(FOOTER_DP) + handleInset();
    }

    /**
     * On gesture navigation the screen runs on underneath Android's handle,
     * and so does the end; the marker only keeps its text clear of it. A
     * button bar is Android's own and is never covered.
     */
    private int handleInset() {
        int inset = navigationBarHeight();
        return inset <= dp(24) ? inset : 0;
    }

    /** The lowest pixel END may draw on. */
    private int bottomEdge() {
        int inset = navigationBarHeight();
        return inset <= dp(24) ? realScreenHeight() : realScreenHeight() - inset;
    }

    /**
     * Where the end line sits on screen when the list stands exactly at its
     * end: the END is the bottom of the screen. The marker occupies the last
     * strip of it, over the app's own bottom bar if it has one, because a page
     * that has ended has nothing underneath it. Should a list stop higher up
     * than that, the line sits on the list's bottom edge and the black runs
     * on down, so nothing past the end can show between the two.
     */
    private int restLine(Rect listRect) {
        int listBottom = Math.min(listRect.bottom, bottomEdge());
        return Math.min(listBottom, bottomEdge() - markerHeight() - dp(2));
    }

    private void relayoutCurtain(FeedState state, boolean atRest) {
        if (curtain == null || curtainParams == null) return;

        Rect area = curtainArea(state);
        // The list's top edge comes and goes with the app's own bars, and the
        // marker hangs from the bottom edge. So the window never shrinks from
        // the top; it only grows that way.
        area.top = Math.min(area.top, curtainParams.y);
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

    /** From the top of the list to the bottom of the screen. */
    private Rect curtainArea(FeedState state) {
        Rect feed = listArea(state);

        Rect area = new Rect();
        area.left = Math.max(0, feed.left);
        area.right = Math.min(screenWidth, feed.right);
        area.top = Math.max(statusBarHeight(), feed.top);
        area.bottom = bottomEdge();
        if (area.bottom - area.top < dp(120)) area.top = area.bottom - dp(120);
        return area;
    }

    /** The list's own band, for the fence, which must leave the app's bars alone. */
    private Rect listBand(FeedState state) {
        Rect feed = listArea(state);
        int screenBottom = realScreenHeight() - navigationBarHeight();

        Rect area = new Rect();
        area.left = Math.max(0, feed.left);
        area.right = Math.min(screenWidth, feed.right);
        area.top = Math.max(statusBarHeight(), feed.top);
        area.bottom = Math.min(feed.bottom, screenBottom);
        if (area.bottom - area.top < dp(120)) area.bottom = area.top + dp(120);
        return area;
    }

    private void attachGuard() {
        if (markerGuard != null || windowManager == null) return;

        View view = new View(this);
        view.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private long downAt;

            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX();
                    downY = event.getRawY();
                    downAt = event.getEventTime();
                } else if (action == MotionEvent.ACTION_UP) {
                    boolean tap = event.getEventTime() - downAt < 500L
                            && Math.abs(event.getRawX() - downX) < dp(12)
                            && Math.abs(event.getRawY() - downY) < dp(12);
                    if (tap && onExitPill(event.getRawX(), event.getRawY())) openExit();
                }
                return true;
            }
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = realScreenHeight() + dp(8);   // parked off screen until placed

        try {
            windowManager.addView(view, lp);
            markerGuard = view;
            guardParams = lp;
            guardTop = Integer.MIN_VALUE;
        } catch (Exception e) {
            log("GUARD failed", e);
        }
    }

    private boolean onExitPill(float x, float y) {
        if (exitPill == null || !exitPill.isShown()) return false;
        int[] at = new int[2];
        exitPill.getLocationOnScreen(at);
        int margin = dp(12);
        return x >= at[0] - margin && x <= at[0] + exitPill.getWidth() + margin
                && y >= at[1] - margin && y <= at[1] + exitPill.getHeight() + margin;
    }

    /**
     * Open the way out: an ordinary link, handed to the app the feed is in.
     * The list that comes on screen next is that way out and gets no end.
     */
    private void openExit() {
        FeedState state = wallKey == null ? null : feeds.get(wallKey);
        if (state == null) return;
        String pkg = Feeds.packageOfKey(state.key);
        Feeds.Exit exit = Feeds.exitFor(pkg);
        if (exit == null) return;

        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(exit.url));
            intent.setPackage(pkg);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            state.openExit(System.currentTimeMillis() + 30_000L);
            log("EXIT to " + exit.label + " key=" + state.key);
        } catch (Exception e) {
            log("EXIT failed key=" + state.key, e);
        }
    }

    /** Keep the guard on the part of the marker that is on screen. */
    private void placeGuard(int translation) {
        if (markerGuard == null || guardParams == null || curtainParams == null) return;

        int top = curtainParams.y + translation;
        int bottom = Math.min(curtainParams.y + curtainHeight, top + dp(2) + markerHeight());
        int height = bottom - top;
        if (height < dp(8)) {
            top = realScreenHeight() + dp(8);
            height = 1;
        }
        if (Math.abs(top - guardTop) < 2 && height == guardHeight) return;

        guardTop = top;
        guardHeight = height;
        guardParams.x = curtainParams.x;
        guardParams.width = curtainParams.width;
        guardParams.y = top;
        guardParams.height = height;
        try {
            windowManager.updateViewLayout(markerGuard, guardParams);
        } catch (Exception ignored) {
            // The guard is gone; the end is coming down.
        }
    }

    /** Bounds of the tracked list; before any list has moved, the best guess. */
    private Rect listArea(FeedState state) {
        FeedState.ListTrack list = state.list;
        if (list != null && list.bottom - list.top >= dp(120)) {
            return new Rect(list.left, list.top, list.right, list.bottom);
        }

        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo node = null;
        try {
            root = getRootInActiveWindow();
            node = root == null ? null : largestScrollable(root);
            if (node != null) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.height() >= dp(120) && bounds.width() >= screenWidth / 2) return bounds;
            }
        } catch (Exception ignored) {
            // Fall through to the band.
        } finally {
            if (node != null && node != root) node.recycle();
            if (root != null) root.recycle();
        }

        int top = statusBarHeight() + dp(TOP_STRIP_DP);
        int bottom = realScreenHeight() - navigationBarHeight() - dp(BOTTOM_STRIP_DP);
        return new Rect(0, top, screenWidth, Math.max(top + dp(120), bottom));
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
        Rect area = listBand(stateFor(key));
        top = area.top;
        height = area.height();

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

            stepsBack += forward ? -1 : 1;
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

        String viewId = state == null || state.list == null ? null : state.list.viewId;
        if (viewId != null) {
            try {
                List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(viewId);
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
        handler.removeCallbacks(settle);
        handler.removeCallbacks(springTimeout);
        springing = false;
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
        if (markerGuard != null && windowManager != null) {
            try {
                windowManager.removeView(markerGuard);
            } catch (Exception ignored) {
                // The window was already gone.
            }
        }
        markerGuard = null;
        guardParams = null;
        exitPill = null;
        guardTop = Integer.MIN_VALUE;
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
