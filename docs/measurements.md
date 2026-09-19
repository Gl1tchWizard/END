# What feeds really report

Measured on 19 September 2026 with `RawLog` on a Samsung SM-A566B, Android 16,
One UI, gesture navigation. YouTube 21.37.42, Brave 1.95.102. Everything in
`FeedState` and in the end itself is built on this page. When an app behaves
differently from what is written here, measure it the same way before
changing any code:

    adb shell setprop log.tag.ENDRAW DEBUG
    adb shell setprop log.tag.ENDPASSIVE DEBUG    # END only watches
    adb logcat -s ENDRAW ENDDBG

## Scroll events

| Surface | Node | Deltas | Offset | Indices | Scroll actions |
|---|---|---|---|---|---|
| YouTube app, Home | `id/results`, RecyclerView | exact pixels | always 0 | from, to, count | forward, backward |
| YouTube app, Shorts | `id/reel_recycler`, RecyclerView | exact pixels | always 0 | none | none, not scrollable |
| Brave, any page | WebView, no id | unset, -1 and -1 | exact absolute pixels | none | up, down, forward, backward |
| Brave, any page | FrameLayout around the page | exact pixels | always 0 | none | none |

- Events arrive about every 108 ms from a RecyclerView and every 165 ms from a
  WebView. A hard fling moves 1300 px between two events.
- One Short is exactly the height of the pager: 2062 px, three events. One
  page in four summed to 2318 px instead.
- Brave reports every movement twice, from the page and from the frame around
  it. The deltas of the frame add up to the offset of the page, to the pixel.
- `flagIncludeNotImportantViews` is not needed. With it YouTube Shorts adds
  two more nodes that repeat the pager.
- The first event of a list is often a layout pass with delta 0 and offset 0.
- Horizontal shelves inside the Home list send events with a vertical delta
  of 0.
- A list that comes back on screen puts itself where it was with one scroll:
  tapping the Home tab produced a single event of 1159 px.

## What that broke in v16 to v18

- YouTube was listed as an item feed, so Home counted one screen per video
  row and ended after two rows.
- The first event, delta 0 and offset 0, was taken as proof that the list
  reports offsets. From then on the position was read from the offset, which
  is always 0: `END REACHED at=0.0 px`, an overshoot of 0 for ever, a curtain
  that never moved and a hold that never fired.
- A burst of content changes arrives before the first scroll event of the
  same swipe, so one Short counted as 1.0 from the burst plus 0.5 from the
  deltas.
- In Brave both nodes were counted, which doubled the speed, and both moved
  one position in two different units, which produced ends such as -3681 px.
- The feed node was learned again every two seconds from whatever scrolled,
  including Brave's tab switcher.

## Layout

- The YouTube Home list runs to the bottom of the screen, under YouTube's own
  navigation bar (`id/bottom_bar_container`, from 2163). The list's bounds
  alone put the end marker on top of that bar.
- While Home scrolls its top bar hides and the list's top edge goes from 371
  to 0. A drag downward first pulls that bar back in: 302 px of drag moved
  the list 39 px, and the bar took 320 px where its height is 270.
- YouTube keeps the list of the next tab alive off screen, zero pixels wide.
  A tree walk that enters it runs out of budget before reaching the bottom bar.
- A drag that starts on the top bar or the chips row does not move the list.
- In Shorts the seek bar is wide, shallow and on the bottom edge, like a bar.

## Foreground

- A window state change is not a foreground change. Overlays, the screenshot
  toolbar and edge panels announce windows and used to take the end off the
  screen for half a second.
- With Brave's toolbar hidden the address bar cannot be read, so after any
  flicker of focus the site was forgotten and the end came down.

## Injected gestures

- A stroke that holds still is reported complete at once, whatever its
  duration. A stroke that moves takes its full duration.
- Touch slop: twice END's own `getScaledTouchSlop()` lands on the end.
- An injected touch on a flinging RecyclerView stops it. In Brave the page had
  already travelled up to 11000 px by the time the first event arrived; paging
  back with `ACTION_SCROLL_BACKWARD` and one exact drag brings it home.
- `adb shell input swipe` does not pass the accessibility input filter. What
  an injected touch does to a real finger that is still on the glass cannot
  be tested from adb and has to be tried by hand.

## Not measured

Instagram, TikTok, Reddit, X, Facebook and LinkedIn are not on the test
phone. Their lists will be followed by the same rules, deltas first and an
absolute offset otherwise, but nobody has looked at what they report.
