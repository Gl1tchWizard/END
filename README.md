# END 1.0, make feeds finite

**END, life is not infinite**
by WillSwitch

Roughly half the waking population scrolls a feed that is designed never to run
out. END puts a bottom on it. You choose how much feed exists, and when that is
used up, the page stops.

## What it does

One rule: after the chosen amount of feed, the scroll ends.

- Default: 3 screens, then 20 minutes of no feed. Both adjustable.
- The end is an end. A marker sits on the exact pixel where the limit fell
  and everything past it is covered. Push past it and the page comes straight
  back, the way an ordinary web page comes back from an overscroll. Scroll up
  and the marker leaves through the bottom edge like any footer.
- Nothing is intercepted. Every tap and every swipe goes to the app, and
  scrolling back is the app's own scrolling. The one thing END does to the
  app is bring its list back when it has gone past the end.
- Feeds are separate. Ending Reddit does not end Instagram. Ending reddit.com
  in Brave does not end the Reddit app.

Works on Android 9 and up.

## How the end works

Everything below follows from what feeds were measured to report, which is
written down in `docs/measurements.md`. Seventeen versions guessed; read that
page before changing any of this.

**Which list.** One app holds several lists: a home list, a Shorts pager, a
page in a browser. END follows the one being scrolled. The node behind a
scroll event is fetched once per movement, never per event, because a fetch is
a round trip into the app's UI thread and doing that per frame is felt in the
app. A browser reports every movement twice, from the page and from a frame
around it; END follows the node that can scroll and ignores its echo.

**Where it stands.** In pixels. A RecyclerView reports exact deltas and an
offset that is always 0, so its position is the sum of its deltas. A web page
reports no deltas and an exact absolute offset, so its position is that
offset. Horizontal shelves, layout passes and a list putting itself back in
place after a tab change are not movement.

**The end.** At the limit the end is pinned at the exact pixel where the limit
fell, even when the event that crossed it was half a screen further on. A
window that takes no touches covers the visible part of the list: its bounds,
above any bar the app draws over its bottom edge. Inside it a sheet hangs from
the end: a thin acid line, an END OF SCROLLING marker, opaque black below.

**Past the end.** The sheet follows every scroll event, so nothing past the
end is ever shown for longer than the tenth of a second an event takes. When
the list moves forward past the end END touches it, which stops a fling at
once, and drags it back by exactly the overshoot, ending on a crawl so nothing
flings back. When a finger is on the screen Android cancels that touch, and
END tries again when the list comes to rest. A list that a hard fling carried
several screens past the end is first paged back with the node's own
`ACTION_SCROLL_BACKWARD`.

Backward movement is never answered. Scrolling back through what you already
saw is the app's own scrolling, untouched, which is why it stays smooth.

Every other list of an ended feed ends where it stands when it is first seen,
so switching tabs does not open a fresh feed.

A feed that is known to report no scrolling at all falls back to a fence: a
touch-owning overlay that steps a page on a swipe and hands taps through. It
is the only mode that takes the touches away from the app, and no feed that
scrolls ever gets it. The log and the status card say which a feed got.

## Counting

Progress is how far the list has travelled, in screens of the list's own
height. One Short is exactly one screen; a continuous feed counts pixels. The
furthest point counts, so reading back and coming down again costs nothing,
and a fling counts for everything it travelled.

For a feed that never reports scrolling, a dense burst of content changes is
one screen. That fallback goes silent for good once a feed has reported real
movement, and it is only credited if no scroll event follows it: content
changes arrive at once and the scroll event about the same swipe a tenth of a
second later, which used to count a Short one and a half times.

Both only count when the screen was touched just before. A one pixel window
asks Android to report touches outside itself; Android delivers those as
`ACTION_OUTSIDE` without coordinates. That is enough to tell scrolling from
autoplay, refreshes and progress bars, and it is why `notificationTimeout` is
0 in the service config: a timeout would coalesce the burst into nothing.

## Browsers

END recognises feed sites inside Chrome, Chrome Beta, Brave, Firefox, Fenix,
Samsung Internet, Edge, Opera, Vivaldi and DuckDuckGo, for instagram.com,
reddit.com, x.com, twitter.com, tiktok.com, facebook.com, youtube.com,
threads.net and linkedin.com.

To know which site is on screen, END reads one node: the address bar, by view
id, at most about once per second, keeping only the host. The page is never
read. Switchable in the app, and with the switch off END never reads it.
While the address bar is hidden END assumes the browser still shows the site
it showed last.

## Test

1. Run from Android Studio, or `gradlew installDebug`, then check the service
   under Settings, Accessibility, END. The status card shows the version.
2. Set LIMIT to 1. "Reset all feeds now" starts every feed over.
3. YouTube Home: scroll one screen. The marker appears above YouTube's own
   navigation bar. Push on: black follows the content up and the list comes
   back to the marker. Scroll up: the marker leaves, scrolling is native.
   Scroll down again: it returns at the same place.
4. YouTube Shorts: swipe to one new Short. Swipe again: the next Short starts
   to come up under black and the same Short returns.
5. youtube.com in Brave: the same as Home, with the marker above Brave's
   toolbar.

Filter Logcat on `tag:ENDDBG`:

    SERVICE CONNECTED
    FEED key=...
    FEED LIST ...id/results [0,0][1080,2340] class=...RecyclerView
    PROGRESS key=... 0,58 / 1 from deltas
    END REACHED key=... end=2915px at=2763px from deltas
    CURTAIN UP key=... area=[0,101][1080,2163] above a bar at 2163
    SPRING brake over=865px drag=865px from 540,806 in 270ms

`./gradlew testDebugUnitTest` runs the measured event sequences against the
counting. For a feed that misbehaves, switch on the raw log and measure before
changing anything:

    adb shell setprop log.tag.ENDRAW DEBUG
    adb logcat -s ENDRAW ENDDBG

## Distribution

Two channels, one key. Google Play for reach, GitHub Releases for anyone who
would rather not use it. Play permits the AccessibilityService API outside
accessibility tools as long as the app declares `isAccessibilityTool="false"`,
completes the permission declaration and shows a prominent in-app disclosure,
all of which END does. It executes nothing on the user's behalf, which is what
the October 2025 policy update turns on; the one gesture it dispatches, the
drag that brings an overscrolled list back to its end, is declared in
`docs/play-submission.md`.

Generate the release key yourself and upload it to Play App Signing, so both
channels carry the same signature and people can switch without uninstalling.
See the comment at the top of `app/build.gradle`.

`docs/play-submission.md` holds every Console answer, the declaration text and
the reviewer note. `docs/privacy.md` is the privacy policy the listing links to.

Register in the Android Developer Console regardless of channel: from
30 September 2026 certified devices block normal installs of apps from
unverified developers, sideloaded ones included.

The workflow in `.github/workflows` builds a debug APK on every push. On a tag
`v*` it builds a signed release APK, verifies the signature and attaches it to
a GitHub Release, which Obtainium tracks directly. That job needs four
repository secrets: `END_KEYSTORE_BASE64` (the keystore, `base64 -w0`),
`END_KEYSTORE_PASSWORD`, `END_KEY_ALIAS` and `END_KEY_PASSWORD`.

## Licence

GPLv3. See `LICENSE`. Free to use, study, change and share; a closed fork with
tracking in it is not permitted.
