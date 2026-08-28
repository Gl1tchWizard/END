package app.willswitch.end;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class EndPrefs {
    private EndPrefs() {}

    private static final String PREFS = "end_prefs";
    private static final String KEY_SCREENS = "screens";
    private static final String KEY_COOLDOWN = "cooldown_minutes";
    private static final String KEY_CONSENT = "disclosure_accepted";
    private static final String KEY_ENABLED_APPS = "enabled_apps";

    public static final String[] DEFAULT_PACKAGES = new String[] {
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.reddit.frontpage",
            "com.twitter.android",
            "com.google.android.youtube",
            "com.facebook.katana",
            "com.instagram.barcelona",
            "com.linkedin.android",
            "com.snapchat.android"
    };

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        SharedPreferences p = prefs(c);
        Set<String> stored = p.getStringSet(KEY_ENABLED_APPS, null);
        if (stored == null) return new HashSet<>(Arrays.asList(DEFAULT_PACKAGES));
        return new HashSet<>(stored);
    }

    public static boolean isProtected(Context c, String pkg) {
        return enabledApps(c).contains(pkg);
    }

    public static void setProtected(Context c, String pkg, boolean enabled) {
        Set<String> set = enabledApps(c);
        if (enabled) set.add(pkg); else set.remove(pkg);
        prefs(c).edit().putStringSet(KEY_ENABLED_APPS, set).apply();
    }


    private static String debugCountKey(String pkg) { return "debug_count:" + pkg; }
    private static String debugLastTypeKey(String pkg) { return "debug_last_type:" + pkg; }
    private static String debugLastAtKey(String pkg) { return "debug_last_at:" + pkg; }

    public static void recordDebugEvent(Context c, String pkg, String type) {
        SharedPreferences p = prefs(c);
        int count = p.getInt(debugCountKey(pkg), 0) + 1;
        p.edit()
                .putInt(debugCountKey(pkg), count)
                .putString(debugLastTypeKey(pkg), type)
                .putLong(debugLastAtKey(pkg), System.currentTimeMillis())
                .apply();
    }

    public static int debugCount(Context c, String pkg) {
        return prefs(c).getInt(debugCountKey(pkg), 0);
    }

    public static String debugLastType(Context c, String pkg) {
        return prefs(c).getString(debugLastTypeKey(pkg), "—");
    }

    public static long debugLastAt(Context c, String pkg) {
        return prefs(c).getLong(debugLastAtKey(pkg), 0L);
    }

    public static void clearDebug(Context c) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        for (String pkg : DEFAULT_PACKAGES) {
            e.remove(debugCountKey(pkg));
            e.remove(debugLastTypeKey(pkg));
            e.remove(debugLastAtKey(pkg));
        }
        e.apply();
    }

    private static String progressKey(String pkg) { return "progress_milli:" + pkg; }
    private static String lockKey(String pkg) { return "locked_until:" + pkg; }
    private static String activityKey(String pkg) { return "last_activity:" + pkg; }

    public static float progress(Context c, String pkg) {
        return prefs(c).getInt(progressKey(pkg), 0) / 1000f;
    }

    public static void setProgress(Context c, String pkg, float value) {
        int milli = Math.max(0, Math.round(value * 1000f));
        prefs(c).edit().putInt(progressKey(pkg), milli).apply();
    }

    public static long lockedUntil(Context c, String pkg) {
        return prefs(c).getLong(lockKey(pkg), 0L);
    }

    public static void setLockedUntil(Context c, String pkg, long value) {
        prefs(c).edit().putLong(lockKey(pkg), value).apply();
    }

    public static long lastActivity(Context c, String pkg) {
        return prefs(c).getLong(activityKey(pkg), 0L);
    }

    public static void setLastActivity(Context c, String pkg, long value) {
        prefs(c).edit().putLong(activityKey(pkg), value).apply();
    }

    public static void clearSession(Context c, String pkg) {
        prefs(c).edit()
                .remove(progressKey(pkg))
                .remove(lockKey(pkg))
                .remove(activityKey(pkg))
                .apply();
    }
}
