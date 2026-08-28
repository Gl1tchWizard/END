# END Android v0.9, finite feed

**END, life is not infinite**
by WillSwitch

Roughly half the waking population scrolls a feed that is designed never to run
out. END puts a bottom on it. You choose how much feed exists, and once that is
used up the stream ends: a banner marks the bottom, and pushing further brings
you back to it.

## Behaviour

Before the limit:
- END counts feed progress. Default limit is 3 screens.
- Default reset is 20 minutes without touching that feed.
- Nothing is intercepted.

At the limit:
- a banner across the bottom of the stream says you have reached the end, with
  a countdown to the next feed;
- scrolling back, tapping, playing, navigating and leaving the app all keep
  working exactly as before;
- driving the feed past the end brings it back to the end, the way a list
  behaves when it runs out.

Feeds are tracked separately. Ending Reddit does not end Instagram, and ending
reddit.com in Brave does not end the Reddit app.

## Why the wall changed in v0.9

v0.8 armed `setMotionEventSources` at the limit and injected every allowed
gesture back with `dispatchGesture`. Android offers no way to pass a captured
touch event through selectively, so every tap and every backward swipe had to
be re-created. On devices where an injected stroke does not reach the app while
the same service is capturing the touchscreen, nothing reaches the app at all
and it freezes. That is what happened in Brave: Reddit stopped, and so did
scrolling back.

v0.9 never touches input. The feed position where the limit was reached is
pinned, and when a scroll event shows the feed has gone past it, END sends
`ACTION_SCROLL_BACKWARD` to the scrollable node that moved. Touch handling
stays entirely with the app.

Three consequences:
- nothing can freeze, because nothing is intercepted;
- scrolling back is native, so it is smooth;
- the wall works on Android 9 and up instead of Android 14 and up.

The trade-off is honest: pushing past the end is a bounce, not a hard stop. You
see a moment of movement before the feed returns. That is how the bottom of a
list already behaves on Android.

## Counting

`TYPE_VIEW_SCROLLED` is the primary signal, with an item-based or pixel-based
profile per feed and a cap of 1.5 screens per 250 ms so one fling cannot
exhaust the limit.

Some apps, YouTube among them, never emit scroll events for their feed. For
those only, a dense burst of content changes counts as one screen. That
fallback switches itself off permanently for any feed that has ever produced a
real scroll event, so it cannot double count on Reddit or Instagram.

## Browsers

END recognises feed sites inside Chrome, Chrome Beta, Brave, Firefox, Fenix,
Samsung Internet, Edge, Opera, Vivaldi and DuckDuckGo, for instagram.com,
reddit.com, x.com, twitter.com, tiktok.com, facebook.com, youtube.com,
threads.net and linkedin.com.

To know which site is on screen, END reads one node: the address bar, by view
id, at most about once per second, keeping only the host. The page is never
read. Switchable in the app, and with the switch off END never reads it.

## Test

1. Run from Android Studio, then switch the service on under
   Settings, Accessibility, END. Android disables it on every reinstall.
2. Set LIMIT to 1.
3. YouTube Shorts: swipe to one new Short. The banner should appear. Swipe up
   again, you should come back to the same Short. Swipe down, the previous
   Short should return smoothly. Tap a control, it should respond.
4. reddit.com in Brave: same, and check that the banner names the site.

Filter Logcat on `tag:ENDDBG`:

    SERVICE CONNECTED
    FEED key=...
    PROGRESS key=... => 2.0 / 3
    END REACHED key=...
    WALL pushed back key=... ok=true
    WALL no scrollable node key=...

`WALL no scrollable node` means the app hides its list from the accessibility
tree and needs a per-app rule.

## Distribution

Google Play restricts the accessibility APIs to services that exist for
accessibility. END declares `isAccessibilityTool="false"` because that is the
truth, which makes a Play listing unlikely to survive review. Direct APK,
F-Droid and Obtainium are the realistic channels.
