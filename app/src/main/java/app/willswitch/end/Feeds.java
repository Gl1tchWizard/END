package app.willswitch.end;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Static knowledge about which apps carry feeds and how to recognise a feed
 * site inside a browser. How a feed reports its scrolling is not listed here:
 * every list says that itself, in its own scroll events.
 *
 * Nothing here touches disk or Android services, so it is safe to call from
 * the input and accessibility hot paths.
 */
public final class Feeds {
    private Feeds() {}

    public static final String SYSTEM_UI = "com.android.systemui";

    public static final String[] DEFAULT_APPS = new String[] {
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

    /** Browsers END can recognise a feed site in. */
    public static final String[] BROWSERS = new String[] {
            "com.android.chrome",
            "com.chrome.beta",
            "com.brave.browser",
            "org.mozilla.firefox",
            "org.mozilla.fenix",
            "com.sec.android.app.sbrowser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.vivaldi.browser",
            "com.duckduckgo.mobile.android"
    };

    /** Feed sites END limits inside a browser. Registrable domains only. */
    public static final String[] DEFAULT_HOSTS = new String[] {
            "instagram.com",
            "reddit.com",
            "x.com",
            "twitter.com",
            "tiktok.com",
            "facebook.com",
            "youtube.com",
            "threads.net",
            "linkedin.com"
    };

    private static final Map<String, String> URL_BAR_IDS;

    static {
        // View ids of the address bar per browser. END reads this one node and
        // keeps only the host from it. Nothing else in the page is queried.
        Map<String, String> ids = new HashMap<>();
        ids.put("com.android.chrome", "com.android.chrome:id/url_bar");
        ids.put("com.chrome.beta", "com.chrome.beta:id/url_bar");
        ids.put("com.brave.browser", "com.brave.browser:id/url_bar");
        ids.put("com.microsoft.emmx", "com.microsoft.emmx:id/url_bar");
        ids.put("com.opera.browser", "com.opera.browser:id/url_field");
        ids.put("com.vivaldi.browser", "com.vivaldi.browser:id/url_bar");
        ids.put("org.mozilla.firefox",
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view");
        ids.put("org.mozilla.fenix",
                "org.mozilla.fenix:id/mozac_browser_toolbar_url_view");
        ids.put("com.sec.android.app.sbrowser",
                "com.sec.android.app.sbrowser:id/location_bar_edit_text");
        ids.put("com.duckduckgo.mobile.android",
                "com.duckduckgo.mobile.android:id/omnibarTextInput");
        URL_BAR_IDS = Collections.unmodifiableMap(ids);
    }

    /**
     * The way out of an app's feed: the part of the same app that no algorithm
     * sorts, as a link the app itself opens. Only links that were tried on a
     * device are listed; a guess that lands on the wrong page is worse than no
     * link. Sites in a browser have none: telling a subscriptions page from the
     * home page would mean reading the path of the address, and END reads only
     * the host.
     */
    public static final class Exit {
        public final String label;
        public final String url;

        Exit(String label, String url) {
            this.label = label;
            this.url = url;
        }
    }

    private static final Map<String, Exit> EXITS;
    private static final Map<String, String> HISTORY_PAGES;

    static {
        Map<String, Exit> exits = new HashMap<>();
        // Tried on YouTube 21.37: opens the Subscriptions page inside the app.
        exits.put("com.google.android.youtube",
                new Exit("SUBSCRIPTIONS", "https://www.youtube.com/feed/subscriptions"));
        EXITS = Collections.unmodifiableMap(exits);

        // Where the platform lets you pause or clear the history it recommends
        // from. END only takes you there.
        Map<String, String> history = new HashMap<>();
        history.put("com.google.android.youtube", "https://myactivity.google.com/product/youtube");
        HISTORY_PAGES = Collections.unmodifiableMap(history);
    }

    public static Exit exitFor(String pkg) {
        return pkg == null ? null : EXITS.get(pkg);
    }

    public static String historyPage(String pkg) {
        return pkg == null ? null : HISTORY_PAGES.get(pkg);
    }

    public static boolean isBrowser(String pkg) {
        return pkg != null && URL_BAR_IDS.containsKey(pkg);
    }

    public static String urlBarId(String pkg) {
        return URL_BAR_IDS.get(pkg);
    }

    /**
     * Key a feed is tracked under. A browser tracks one key per feed site so
     * ending Reddit in Chrome does not end Instagram in the same browser.
     */
    public static String feedKey(String pkg, String host) {
        return host == null ? pkg : pkg + "#" + host;
    }

    public static String packageOfKey(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    /**
     * Pull a bare host out of whatever the address bar shows. Browsers display
     * anything from "reddit.com" to "https://www.reddit.com/r/all?x=1", and
     * while the user is typing they show free text.
     */
    public static String hostOf(CharSequence raw) {
        if (raw == null) return null;

        String value = raw.toString().trim();
        if (value.isEmpty() || value.indexOf(' ') >= 0) return null;

        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);

        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(at + 1);

        int cut = value.length();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                cut = i;
                break;
            }
        }
        value = value.substring(0, cut).toLowerCase();

        if (value.startsWith("www.")) value = value.substring(4);
        if (value.startsWith("m.")) value = value.substring(2);
        if (value.indexOf('.') <= 0 || value.endsWith(".")) return null;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-';
            if (!ok) return null;
        }
        return value;
    }

    /** True when host is one of the enabled sites, or a subdomain of one. */
    public static String matchEnabledHost(String host, Set<String> enabled) {
        if (host == null || enabled == null) return null;
        for (String site : enabled) {
            if (host.equals(site) || host.endsWith("." + site)) return site;
        }
        return null;
    }
}
