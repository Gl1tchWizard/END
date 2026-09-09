# END 1.0, make feeds finite

**END, life is not infinite**
by WillSwitch

Roughly half the waking population scrolls a feed that is designed never to run
out. END puts a bottom on it. You choose how much feed exists, and when that is
used up, the page stops.

## What it does

One rule: after the chosen amount of feed, the scroll ends.

- Default: 3 screens, then 20 minutes of no feed. Both adjustable.
- The end is a curtain. An END footer sits on the bottom edge of the page and
  everything past it is black. Scroll back and it is all still there; scroll
  further and the page simply runs out, the way an ordinary web page does.
- Nothing is intercepted. Every tap and every swipe goes to the app. END
  never touches your input.
- Feeds are separate. Ending Reddit does not end Instagram. Ending reddit.com
  in Brave does not end the Reddit app.

Works on Android 9 and up.

## How the curtain works

END learns which scrollable node carries the feed from the feed's own scroll
events, its bounds on screen, and its position: exact pixel deltas when the
app reports them, its scroll offset otherwise, an item index as a last resort.

At the limit the end is pinned at the current position. A window that takes
no touches is placed over the feed's own area. Inside it a sheet slides: an
acid line, the END footer, and black below. At the end the footer sits on the
bottom edge of the feed. Every scroll event moves the sheet by the same
amount the content moved, so past the end the footer rises and the black
grows, and on the way back it settles again. A small lead is added while
moving forward so event latency never shows a strip of new content.

Because the window is untouchable, scrolling, tapping, long pressing and the
app's own bars all work natively.

Past the end the feed springs back, like a web page after an overscroll. When
the feed comes to rest past the end, END first asks the feed's own node to
scroll back; if the app refuses, END drags the feed back by the overshoot
distance with a slow injected swipe that ends at zero velocity, so it cannot
fling. That drag is the only gesture END ever performs, it happens only as the
direct result of the user's own overscroll, and it stops after three tries.

Feeds that report no position at all fall back to a fence: a touch-owning
overlay that steps a page on a swipe and hands taps through. The log says
which of the two a feed got.

## Counting

Two signals, never both for the same movement:

1. `TYPE_VIEW_SCROLLED`, with an item-based or pixel-based profile per feed.
   Exact for Reddit, Instagram, X, LinkedIn and web pages.
2. A dense burst of content changes, for feeds that never report scrolling.
   YouTube Shorts is the main one. This signal is silenced for 1.5 s after
   every real scroll event, so it cannot double count.

Both only count when the screen was touched just before. A one pixel window
asks Android to report touches outside itself; Android delivers those as
`ACTION_OUTSIDE` without coordinates. That is enough to tell scrolling from
autoplay, refreshes and progress bars, and it is why `notificationTimeout` is
0 in the service config: a timeout would coalesce the burst into nothing.

Accumulation is capped at 1.5 screens per 250 ms so one fling cannot exhaust
the limit.

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
3. YouTube Shorts: swipe to one new Short. The wall should come up. Tap it,
   you should land on the home screen. Open YouTube again: wall, with the
   countdown continuing.
4. reddit.com in Brave: scroll one screen. Wall, headed REDDIT.COM HAS ENDED.
   Tap it: back to the previous page. Open another site: no wall.

Filter Logcat on `tag:ENDDBG`:

    SERVICE CONNECTED
    FEED key=...
    PROGRESS key=... => 2.0 / 3
    END REACHED key=...
    WALL UP key=...
    WALL leave key=...

If PROGRESS never appears in an app while you scroll, that app reports
neither scroll events nor content bursts and needs a per-app rule. Say which.

## Distribution

Two channels, one key. Google Play for reach, GitHub Releases for anyone who
would rather not use it. Play permits the AccessibilityService API outside
accessibility tools as long as the app declares `isAccessibilityTool="false"`,
completes the permission declaration and shows a prominent in-app disclosure,
all of which END does. It performs no gestures and executes nothing on the
user's behalf, which is what the October 2025 policy update turns on.

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
