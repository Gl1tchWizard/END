# END Android v0.8, finite feed

**END, life is not infinite**
by WillSwitch

Roughly half the waking population now scrolls a feed that is designed never to
run out. END puts a bottom on it. You choose how much feed exists, and when
that is used up the feed stops producing new content while the app stays
usable.

v0.8 keeps the behaviour v0.7 aimed at and rebuilds the machinery underneath
it, because the v0.7 implementation made scrolling feel heavy.

## Behaviour

Before the limit:
- END counts feed progress from scroll events. Default limit is 3 screens.
- Default reset is 20 minutes of not touching that feed.
- Nothing is intercepted. Input is untouched.

At the limit:
- the current feed stays on screen and the app stays usable;
- a small non-touchable END badge appears with a countdown;
- on Android 14+ END arms the finite-feed input guard.

After the limit on Android 14+:
- finger swipe up, which asks for new content, is dropped;
- finger swipe down, which returns to earlier content, passes through;
- taps, long presses and drags pass through;
- horizontal swipes pass through;
- Android back, home, recents and the notification shade keep working.

Feeds are tracked separately. Ending Reddit does not end Instagram, and ending
reddit.com in Chrome does not end the Reddit app.

## Browsers

END recognises feed sites inside Chrome, Chrome Beta, Brave, Firefox, Fenix,
Samsung Internet, Edge, Opera, Vivaldi and DuckDuckGo. The same limit and the
same wall apply to instagram.com, reddit.com, x.com, twitter.com, tiktok.com,
facebook.com, youtube.com, threads.net and linkedin.com.

To know which site is on screen, END reads one node: the browser address bar,
by view id, at most about once per second, and keeps only the host. The page
itself is never queried. This is switchable in the app, and with the switch off
END never reads it.

## What changed since v0.7

**Nothing on the hot path touches disk.** v0.7 called into SharedPreferences
from `onMotionEvent`, which meant a disk read and a fresh `HashSet` allocation
for every `ACTION_MOVE`, plus a read-modify-write per accessibility event for
debug counters, on top of `typeAllMask` with a zero notification timeout.
Settings now live in a cached snapshot rebuilt only on change, feed progress
lives in memory, and only the lock transition is written.

**Allowed gestures are mirrored live.** v0.7 buffered the whole gesture, waited
for `ACTION_UP` and then dispatched a single straight line from the down point
to the up point with a clamped duration. Every intermediate point and all
velocity were lost, so an allowed swipe arrived after the finger had already
left the glass. `GestureMirror` now opens a continued stroke as soon as the
gesture is classified and keeps appending real segments to it.

**The guard is armed on transitions only.** v0.7 called `setServiceInfo` twice
per allowed gesture, once to release capture before injecting and once to
restore it 45 ms later. That is a binder round trip into the system
accessibility service, and the gap between the two let real forward swipes
through. The guard now changes state on foreground and lock transitions only,
and the call is skipped when nothing changed.

**No gesture is dropped.** v0.7 returned early from `onMotionEvent` for the
whole duration of a dispatch, so any touch during a replay vanished.

**System gestures use global actions.** An injected stroke from the screen edge
does not trigger Android navigation, so v0.7 replayed edge swipes as ordinary
gestures and navigation felt broken. Edge gestures now map to
`GLOBAL_ACTION_BACK`, `HOME`, `RECENTS` and `NOTIFICATIONS`.

**The notification shade is released.** v0.7 ignored System UI window changes,
so the guard stayed armed on the feed app while the shade was open and swipes
in the shade were captured.

**One counting model.** v0.7 combined scroll deltas, item indices, content
change bursts and gesture duration, all of which could fire for the same swipe,
and one fallback path counted a full screen per burst. Counting now listens to
`TYPE_VIEW_SCROLLED` only, uses a per-app profile of item based or pixel based,
and caps accumulation at 1.5 screens per 250 ms.

**The badge is a small window.** v0.7 used a full screen overlay for a pill.

## Input mirroring probe

Android gives an accessibility service an all or nothing capture: once
`setMotionEventSources` is on, a captured event cannot be selectively passed
through, so allowed gestures have to be injected back. Whether an injected
stroke reaches the app while the same service is capturing the touchscreen is
device dependent and not documented.

END measures it. The first time you hit the wall, it starts a mirrored stroke
and checks whether the app reacts within 800 ms. After three failures it
switches to release-guard mode, where the wall is dropped for the rest of an
allowed swipe instead. The result is shown in the app under END SERVICE and
logged as:

    MIRROR confirmed: injected strokes reach the app
    MIRROR switching to release-guard mode

Check this first on a new device.

## Test on the Samsung

1. Open the project in Android Studio and let Gradle sync.
2. Select the connected Samsung and press Run.
3. If the old service process is still alive, toggle
   Settings, Accessibility, END, off, then on.
4. Set LIMIT to 1 for the first test.
5. Open YouTube Shorts and swipe to one new Short. END should be reached.
6. Then check:
   - swipe up again, the feed should not advance;
   - swipe down, the previous Short should return, smoothly;
   - tap a control, it should respond;
   - swipe from the left edge, back should work;
   - pull down the notification shade, it should behave normally.
7. Open reddit.com in Chrome and repeat.

Filter Logcat on `tag:ENDDBG`. Useful lines:

    SERVICE CONNECTED
    SERVICE INFO events=... guard=true
    PROGRESS key=...
    END REACHED key=...
    FINITE WALL blocked forward swipe
    MIRROR confirmed / MIRROR probe failed

## Older Android

The API 34 mechanism, `AccessibilityService.onMotionEvent` with
`AccessibilityServiceInfo.setMotionEventSources`, is what makes an exact wall
possible. On Android 13 and older END counts feed progress and shows the badge,
and the wall arrives with the version that adds a per-app scroll-node
alternative.

## Distribution

Google Play restricts the accessibility APIs to services that exist for
accessibility. END declares `isAccessibilityTool="false"` because that is the
truth, which makes a Play listing unlikely to survive review. Direct APK,
F-Droid and Obtainium are the realistic channels.
