package app.willswitch.end;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Settings storage.
 *
 * v0.7 called into SharedPreferences from onMotionEvent, which meant a disk
 * read and a fresh HashSet allocation for every ACTION_MOVE. Everything the
 * service needs while a finger is down now comes from {@link Snapshot}, which
 * is rebuilt only when a setting actually changes.
 */
public final class EndPrefs {
    private EndPrefs() {}

    private static final String PREFS = "end_prefs";
    private static final String KEY_SCREENS = "screens";
    private static final String KEY_COOLDOWN = "cooldown_minutes";
    private static final String KEY_CONSENT = "disclosure_accepted";
    private static final String KEY_ENABLED_APPS = "enabled_apps";
    private static final String KEY_ENABLED_HOSTS = "enabled_hosts";
    private static final String KEY_BROWSER_SITES = "browser_sites_enabled";
    private static final String KEY_MIRROR_MODE = "mirror_mode";

    /** Not measured yet. Mirror gestures with the motion guard left on. */
    public static final int MIRROR_UNKNOWN = 0;
    /** Injected strokes provably reach the app while the guard is on. */
    public static final int MIRROR_CONFIRMED = 1;
    /** Injection is swallowed by our own capture. Release the guard instead. */
    public static final int MIRROR_RELEASE_GUARD = 2;

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Immutable view of every setting the service reads while running. */
    public static final class Snapshot {
        public final int screens;
        public final int cooldownMinutes;
        public final Set<String> apps;
        public final Set<String> hosts;
        public final boolean browserSites;
        public final int mirrorMode;

        Snapshot(int screens, int cooldownMinutes, Set<String> apps,
                 Set<String> hosts, boolean browserSites, int mirrorMode) {
            this.screens = screens;
            this.cooldownMinutes = cooldownMinutes;
            this.apps = Collections.unmodifiableSet(apps);
            this.hosts = Collections.unmodifiableSet(hosts);
            this.browserSites = browserSites;
            this.mirrorMode = mirrorMode;
        }

        public boolean isProtectedApp(String pkg) {
            return pkg != null && apps.contains(pkg);
        }

        public long cooldownMillis() {
            return cooldownMinutes * 60_000L;
        }
    }

    public static Snapshot snapshot(Context c) {
        SharedPreferences p = prefs(c);
        return new Snapshot(
                p.getInt(KEY_SCREENS, 3),
                p.getInt(KEY_COOLDOWN, 20),
                readSet(p, KEY_ENABLED_APPS, Feeds.DEFAULT_APPS),
                readSet(p, KEY_ENABLED_HOSTS, Feeds.DEFAULT_HOSTS),
                p.getBoolean(KEY_BROWSER_SITES, true),
                p.getInt(KEY_MIRROR_MODE, MIRROR_UNKNOWN)
        );
    }

    private static Set<String> readSet(SharedPreferences p, String key, String[] fallback) {
        Set<String> stored = p.getStringSet(key, null);
        if (stored == null) return new HashSet<>(Arrays.asList(fallback));
        return new HashSet<>(stored);
    }

    public static int screens(Context c) {
        return prefs(c).getInt(KEY_SCREENS, 3);
    }

    public static void setScreens(Context c, int value) {
        prefs(c).edit().putInt(KEY_SCREENS, value).apply();
    }

    public static int cooldownMinutes(Context c) {
        return prefs(c).getInt(KEY_COOLDOWN, 20);
    }

    public static void setCooldownMinutes(Context c, int value) {
        prefs(c).edit().putInt(KEY_COOLDOWN, value).apply();
    }

    public static boolean disclosureAccepted(Context c) {
        return prefs(c).getBoolean(KEY_CONSENT, false);
    }

    public static void setDisclosureAccepted(Context c, boolean accepted) {
        prefs(c).edit().putBoolean(KEY_CONSENT, accepted).apply();
    }

    public static Set<String> enabledApps(Context c) {
        return readSet(prefs(c), KEY_ENABLED_APPS, Feeds.DEFAULT_APPS);
    }

    public static boolean isProtected(Context c, String pkg) {
        return enabledApps(c).contains(pkg);
    }

    public static void setProtected(Context c, String pkg, boolean enabled) {
        Set<String> set = enabledApps(c);
        if (enabled) set.add(pkg); else set.remove(pkg);
        prefs(c).edit().putStringSet(KEY_ENABLED_APPS, set).apply();
    }

    public static Set<String> enabledHosts(Context c) {
        return readSet(prefs(c), KEY_ENABLED_HOSTS, Feeds.DEFAULT_HOSTS);
    }

    public static boolean isHostEnabled(Context c, String host) {
        return enabledHosts(c).contains(host);
    }

    public static void setHostEnabled(Context c, String host, boolean enabled) {
        Set<String> set = enabledHosts(c);
        if (enabled) set.add(host); else set.remove(host);
        prefs(c).edit().putStringSet(KEY_ENABLED_HOSTS, set).apply();
    }

    public static boolean browserSitesEnabled(Context c) {
        return prefs(c).getBoolean(KEY_BROWSER_SITES, true);
    }

    public static void setBrowserSitesEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_BROWSER_SITES, enabled).apply();
    }

    public static int mirrorMode(Context c) {
        return prefs(c).getInt(KEY_MIRROR_MODE, MIRROR_UNKNOWN);
    }

    public static void setMirrorMode(Context c, int mode) {
        prefs(c).edit().putInt(KEY_MIRROR_MODE, mode).apply();
    }

    // Lock state survives a service restart, so it is the one piece of session
    // state that is persisted. It is written on transitions only, never per
    // event: reaching END, and clearing END.

    private static String lockKey(String feedKey) { return "locked_until:" + feedKey; }

    public static long lockedUntil(Context c, String feedKey) {
        return prefs(c).getLong(lockKey(feedKey), 0L);
    }

    public static void setLockedUntil(Context c, String feedKey, long value) {
        prefs(c).edit().putLong(lockKey(feedKey), value).apply();
    }

    public static void clearLock(Context c, String feedKey) {
        prefs(c).edit().remove(lockKey(feedKey)).apply();
    }
}
