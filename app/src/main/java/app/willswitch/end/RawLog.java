package app.willswitch.end;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Measurement, not behaviour. Prints what an app really reports, so that the
 * counting and the end are built on what was seen instead of what was assumed.
 *
 * Off unless a developer switches it on over adb:
 *
 *   adb shell setprop log.tag.ENDRAW DEBUG     every event type, stock view filter
 *   adb shell setprop log.tag.ENDRAW VERBOSE   same, plus views marked not important
 *   adb shell setprop log.tag.ENDRAW INFO      off again
 *   adb shell setprop log.tag.ENDPASSIVE DEBUG while raw logging is on, END only
 *                                              watches: no counting, no end
 *   adb logcat -s ENDRAW
 *
 * It prints classes, view ids, bounds and scroll numbers. It never prints
 * text or content descriptions, so the log holds nothing a person wrote or read.
 */
final class RawLog {
    private RawLog() {}

    static final String TAG = "ENDRAW";

    private static final long CHECK_EVERY_MS = 2000L;
    private static final long SOURCE_EVERY_MS = 500L;
    private static final long CONTENT_WINDOW_MS = 500L;
    private static final long TREE_EVERY_MS = 5000L;
    private static final int TREE_MAX_NODES = 1500;

    private static int mode;
    private static boolean passive;
    private static long checkedAt;
    private static int baseTypes;
    private static int baseFlags;
    private static boolean baseKnown;

    private static final Map<String, String> SOURCES = new HashMap<>();
    private static final Map<String, Long> SOURCE_AT = new HashMap<>();

    private static String contentPkg;
    private static long contentStart;
    private static int contentCount;
    private static int contentTypes;
    private static final StringBuilder contentClasses = new StringBuilder();

    private static long lastTreeAt;
    private static boolean treePending;

    /** A rebound service starts from its XML again. */
    static void reset() {
        mode = 0;
        checkedAt = 0L;
        baseKnown = false;
    }

    /** True while raw logging is on. Cheap: the property is read every two seconds. */
    static boolean sync(AccessibilityService service) {
        long now = SystemClock.uptimeMillis();
        if (checkedAt == 0L || now - checkedAt > CHECK_EVERY_MS) {
            checkedAt = now;
            int wanted = Log.isLoggable(TAG, Log.VERBOSE) ? 2
                    : Log.isLoggable(TAG, Log.DEBUG) ? 1 : 0;
            if (wanted != mode) apply(service, wanted);
            boolean quiet = wanted != 0 && Log.isLoggable("ENDPASSIVE", Log.DEBUG);
            if (quiet != passive) {
                passive = quiet;
                Log.i(TAG, "RAW passive=" + passive);
            }
        }
        return mode != 0;
    }

    /** True when END should only watch: the app must behave as if END were absent. */
    static boolean passive() {
        return mode != 0 && passive;
    }

    private static void apply(AccessibilityService service, int wanted) {
        try {
            AccessibilityServiceInfo info = service.getServiceInfo();
            if (info == null) return;
            if (!baseKnown) {
                baseTypes = info.eventTypes;
                baseFlags = info.flags;
                baseKnown = true;
            }
            int flags = baseFlags;
            if (wanted != 0) flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            if (wanted == 2) flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.eventTypes = wanted == 0 ? baseTypes : AccessibilityEvent.TYPES_ALL_MASK;
            info.flags = flags;
            service.setServiceInfo(info);
            mode = wanted;
            Log.i(TAG, "RAW mode=" + wanted + " types=0x" + Integer.toHexString(info.eventTypes)
                    + " flags=0x" + Integer.toHexString(flags));
        } catch (Exception e) {
            Log.w(TAG, "RAW mode switch failed", e);
        }
    }

    /** The three event types END is built on. Everything else is only logged. */
    static boolean isCounted(int type) {
        return type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
    }

    static void event(final AccessibilityService service, AccessibilityEvent event, Handler handler) {
        int type = event.getEventType();
        long now = SystemClock.uptimeMillis();
        String pkg = String.valueOf(event.getPackageName());

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            content(pkg, event, now);
        } else {
            flushContent();
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                scrolled(pkg, event, now);
            } else {
                Log.i(TAG, AccessibilityEvent.eventTypeToString(type)
                        + " " + pkg + " cls=" + event.getClassName()
                        + " win=" + event.getWindowId()
                        + (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                            ? " changes=0x" + Integer.toHexString(event.getWindowChanges()) : "")
                        + " from=" + event.getFromIndex() + " to=" + event.getToIndex()
                        + " n=" + event.getItemCount());
            }
        }

        boolean stateChange = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        boolean due = now - lastTreeAt > TREE_EVERY_MS;
        if (!treePending && (stateChange || (due && type == AccessibilityEvent.TYPE_VIEW_SCROLLED))) {
            treePending = true;
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    treePending = false;
                    lastTreeAt = SystemClock.uptimeMillis();
                    if (mode != 0) dump(service);
                }
            }, stateChange ? 900L : 50L);
        }
    }

    private static void scrolled(String pkg, AccessibilityEvent event, long now) {
        String signature = pkg + "|" + event.getClassName() + "|" + event.getWindowId()
                + "|" + event.getItemCount();
        Long at = SOURCE_AT.get(signature);
        String source = SOURCES.get(signature);
        boolean fresh = false;

        if (at == null || now - at > SOURCE_EVERY_MS) {
            SOURCE_AT.put(signature, now);
            source = describeSource(event);
            SOURCES.put(signature, source);
            fresh = true;
            if (SOURCES.size() > 64) {
                SOURCES.clear();
                SOURCE_AT.clear();
            }
        }

        Log.i(TAG, "SCROLL " + pkg + " cls=" + event.getClassName()
                + " win=" + event.getWindowId()
                + " dy=" + event.getScrollDeltaY() + " dx=" + event.getScrollDeltaX()
                + " y=" + event.getScrollY() + " maxY=" + event.getMaxScrollY()
                + " x=" + event.getScrollX() + " maxX=" + event.getMaxScrollX()
                + " from=" + event.getFromIndex() + " to=" + event.getToIndex()
                + " n=" + event.getItemCount()
                + " t=" + event.getEventTime()
                + (fresh ? " src=" : " src~") + source);
    }

    private static String describeSource(AccessibilityEvent event) {
        AccessibilityNodeInfo node = null;
        try {
            node = event.getSource();
            if (node == null) return "null";
            return describe(node);
        } catch (Exception e) {
            return "error";
        } finally {
            if (node != null) node.recycle();
        }
    }

    private static String describe(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        StringBuilder out = new StringBuilder();
        out.append(node.getViewIdResourceName())
                .append(' ').append(bounds.toShortString())
                .append(" cls=").append(node.getClassName())
                .append(node.isScrollable() ? " scrollable" : "")
                .append(node.isImportantForAccessibility() ? "" : " NOT-IMPORTANT")
                .append(node.isVisibleToUser() ? "" : " invisible")
                .append(" kids=").append(node.getChildCount());

        AccessibilityNodeInfo.CollectionInfo collection = node.getCollectionInfo();
        if (collection != null) {
            out.append(" rows=").append(collection.getRowCount())
                    .append(" cols=").append(collection.getColumnCount());
        }

        StringBuilder actions = new StringBuilder();
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            int id = action.getId();
            if (id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) actions.append('F');
            else if (id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) actions.append('B');
            else if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()) actions.append('U');
            else if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()) actions.append('D');
            else if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()) actions.append('L');
            else if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()) actions.append('R');
            else if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId()) actions.append('P');
        }
        if (actions.length() > 0) out.append(" act=").append(actions);
        return out.toString();
    }

    private static void content(String pkg, AccessibilityEvent event, long now) {
        if (contentPkg != null && (!contentPkg.equals(pkg) || now - contentStart > CONTENT_WINDOW_MS)) {
            flushContent();
        }
        if (contentPkg == null) {
            contentPkg = pkg;
            contentStart = now;
        }
        contentCount++;
        contentTypes |= event.getContentChangeTypes();
        String cls = String.valueOf(event.getClassName());
        if (contentClasses.length() < 160 && contentClasses.indexOf(cls) < 0) {
            if (contentClasses.length() > 0) contentClasses.append(',');
            contentClasses.append(cls);
        }
    }

    private static void flushContent() {
        if (contentPkg == null) return;
        Log.i(TAG, "CONTENT " + contentPkg + " n=" + contentCount
                + " changes=0x" + Integer.toHexString(contentTypes)
                + " classes=" + contentClasses);
        contentPkg = null;
        contentCount = 0;
        contentTypes = 0;
        contentClasses.setLength(0);
    }

    /** Windows on screen, and every scrollable or list in the active one. */
    private static void dump(AccessibilityService service) {
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            Rect bounds = new Rect();
            for (AccessibilityWindowInfo window : windows) {
                window.getBoundsInScreen(bounds);
                AccessibilityNodeInfo root = window.getRoot();
                Log.i(TAG, "WINDOW id=" + window.getId() + " type=" + window.getType()
                        + " layer=" + window.getLayer()
                        + (window.isActive() ? " active" : "")
                        + (window.isFocused() ? " focused" : "")
                        + " " + bounds.toShortString()
                        + " pkg=" + (root == null ? null : root.getPackageName()));
                if (root != null) root.recycle();
            }
        } catch (Exception e) {
            Log.w(TAG, "WINDOW dump failed", e);
        }

        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null) {
                Log.i(TAG, "TREE no active root");
                return;
            }
            Log.i(TAG, "TREE pkg=" + root.getPackageName());

            ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
            ArrayDeque<Integer> depths = new ArrayDeque<>();
            nodes.push(root);
            depths.push(0);
            int visited = 0;

            while (!nodes.isEmpty() && visited++ < TREE_MAX_NODES) {
                AccessibilityNodeInfo node = nodes.pop();
                int depth = depths.pop();
                if (node == null) continue;

                if (node.isScrollable() || node.getCollectionInfo() != null) {
                    Log.i(TAG, "NODE d=" + depth + " " + describe(node));
                }

                int count = node.getChildCount();
                for (int i = count - 1; i >= 0; i--) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        nodes.push(child);
                        depths.push(depth + 1);
                    }
                }
                if (node != root) node.recycle();
            }
            Log.i(TAG, "TREE end visited=" + visited + (nodes.isEmpty() ? "" : " (cut)"));
            while (!nodes.isEmpty()) {
                AccessibilityNodeInfo left = nodes.pop();
                if (left != null && left != root) left.recycle();
            }
        } catch (Exception e) {
            Log.w(TAG, "TREE dump failed", e);
        } finally {
            if (root != null) root.recycle();
        }
    }
}
