# END Android v0.7 — finite feed

**END — life's not infinite**  
by WillSwitch · made by Glitch Labs

This build changes the core behaviour. It is no longer an app lock or a transparent full-screen blocker.

## What happens now

Before the limit:
- END counts feed progress as before.
- Default: 3 screens.
- Default reset: 20 minutes.

At the limit:
- the current feed remains visible;
- the app remains usable;
- a small non-touchable END badge appears;
- on Android 14+ END starts a **finite-feed input guard**.

After the limit on Android 14+:
- **finger swipe up** (request more/new feed content) → blocked;
- **finger swipe down** (go back to earlier content) → allowed;
- **tap** → allowed;
- **horizontal swipe** → allowed;
- Android/app bottom navigation gestures → allowed.

The important change is that there is now a wall at the bottom of the feed rather than a wall over the whole app.

## Why Android 14+

Android 14 / API 34 added `AccessibilityService.onMotionEvent()` and
`AccessibilityServiceInfo.setMotionEventSources()`. END only enables this
touchscreen capture after the feed limit has been reached.

Captured touchscreen events are withheld from the app by Android. END therefore:
1. discards only forward vertical swipes;
2. temporarily releases capture;
3. replays allowed taps/back-swipes/horizontal gestures with `dispatchGesture()`;
4. restores the finite-feed guard.

The accessibility service declares `canPerformGestures=true`.

## Privacy

- no INTERNET permission;
- no account;
- no analytics;
- `canRetrieveWindowContent=false`;
- END does not read page/app text, images, messages or passwords;
- touch coordinates are used transiently only after the feed limit, to classify
  a gesture as forward/back/tap/horizontal;
- touch coordinates are not stored.

## Test on the Samsung / Android 16

1. Open this project in Android Studio.
2. Let Gradle sync finish.
3. Select the connected Samsung.
4. Press **Run ▶**.
5. If Android kept the old service process alive, toggle:
   **Settings → Accessibility → END → off → on**.
6. In END set **LIMIT = 1** for the first test.
7. Open YouTube Shorts.
8. Swipe to one new Short: END should be reached.
9. Then test:
   - swipe **up** again → should not advance;
   - swipe **down** → should return to the previous Short;
   - tap pause/play or another control → should still work;
   - app navigation should still work.

For debug, filter Logcat with:

    tag:ENDDBG

Useful lines:

    END REACHED
    MOTION GUARD ON
    FINITE WALL blocked forward swipe
    MOTION GUARD OFF

## Older Android

On Android 13 and older, v0.7 cannot pre-filter ordinary touchscreen gestures
with the API 34 mechanism. A best-effort accessibility bounce-back remains,
but the exact finite wall is currently an Android 14+ feature.

## Known MVP rough edges

This is still a proof-of-concept input proxy after the limit. Complex multi-touch
gestures are approximated. The next tuning target is per-app behaviour for
Shorts/Reels/TikTok versus continuous feeds.

Browser URL-specific protection (for example reddit.com inside Brave) is not
part of this build yet.
