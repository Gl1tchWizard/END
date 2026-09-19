# Play Console submission notes

Everything a reviewer or a future maintainer needs, in the order the Console
asks for it. Sources for the policy claims are linked at the bottom.

## Before you start

**Developer verification.** Register in the Android Developer Console before
publishing anywhere, Play or not. From 30 September 2026 certified devices in
Brazil, Indonesia, Singapore and Thailand block normal installs of apps whose
developer is not verified, including sideloaded ones, with global rollout in
2027. This applies to the GitHub release just as much as to Play, so the
registration is unavoidable if END is meant to reach ordinary people.

**Signing.** Generate the release key yourself and upload it to Play App
Signing. Do not let Play generate one. If Play holds a key you do not have, the
Play build and the GitHub build carry different signatures and nobody can move
between the channels without uninstalling. See the comment at the top of
`app/build.gradle`.

## App content declarations

| Question | Answer |
| --- | --- |
| Privacy policy URL | https://willswitch.nl/end/privacy (or the GitHub Pages copy of `docs/privacy.md`) |
| Ads | No |
| App access | All functionality available without login. No account, no gated features. |
| Content rating | Everyone. No user generated content, no communication, no purchases. |
| Target audience | 13+. Not designed for children, though parents may install it. Do not enrol in the Families programme. |
| Data safety | No data collected. No data shared. Nothing transmitted: the app declares no Internet permission. |
| Financial features | None |
| Government app | No |
| News app | No |
| Health app | No |

## Accessibility API declaration

**Is this an accessibility tool?** No. `isAccessibilityTool="false"` in
`app/src/main/res/xml/accessibility_service_config.xml`. END is a digital
wellbeing tool, not an assistive one, and Play reserves the accessibility tool
declaration for services designed to help people with disabilities.

**Why the app needs the AccessibilityService API.** END limits how much of an
infinite feed a user consumes. To do that it has to know that a feed is
scrolling and which app is in the foreground. Android exposes no other API for
either. There is no alternative that would achieve the same result.

**What END reads through the API.**
- The package name of the foreground app, to know whether the user asked for
  this app to be limited.
- Scroll events, to count how far a feed has moved. Magnitude only.
- Content change events, as a fallback count for feeds that never report
  scrolling. Occurrence only, not content.
- The address bar field of supported browsers, only when the user enables
  browser feeds, keeping only the host.

**What it does not do.** END does not read page text, messages, images, form
fields or passwords. It takes no screenshots. It never captures or intercepts
the user's touches; its overlay is untouchable. It performs no automation and
executes nothing on the user's behalf. This matters for the 30 October 2025
policy update, which prohibits any use of the API that lets an app
autonomously initiate, plan and execute actions.

**The one gesture END dispatches.** When the user scrolls a feed past its end,
END returns the feed to the end: first with the node's own scroll-back action,
which is not a gesture, and only if the app refuses that, once the feed is at
rest, with a slow drag of the overshoot distance dispatched through
`dispatchGesture`. This is overscroll
correction, the equivalent of a web page snapping back, triggered solely by
the user's own overscroll a moment earlier, limited to three attempts, and it
never initiates, plans or executes anything else. For feeds that report no
position, a tap the user makes on the fence is passed through as a tap at the
same spot.

**Prominent disclosure.** Shown on first launch, before anything is enabled,
as a dialog the user must actively accept. It is inside the app, appears in
normal use rather than behind a menu, describes what is accessed and why, is
separate from any other disclosure, and is not only in the privacy policy. The
text lives in `MainActivity.showDisclosure`.

**Video for the declaration.** Screen recording, under two minutes:
1. First launch, the disclosure dialog, accepting it.
2. Turning on the accessibility service in Android settings.
3. Setting LIMIT to 1.
4. Scrolling a feed until the END wall appears with its countdown.
5. Tapping the wall to leave.

## Reviewer note

> END makes infinite feeds finite. The user chooses a number of screens and
> which apps it applies to. When that amount has been scrolled, END draws its
> own full screen overlay over the feed with a countdown, and the user taps it
> to leave.
>
> To test: enable END under Settings, Accessibility. In END, set LIMIT to 1 and
> switch on any listed app. Open that app and scroll one screen. The END wall
> appears. Tap it to leave.
>
> The app has no account and no Internet permission. All state is local. The
> full source is at https://github.com/WillSwitch/END-Android.

## Sources

- Use of the AccessibilityService API:
  https://support.google.com/googleplay/android-developer/answer/10964491
- Policy announcement, 30 October 2025, accessibility API and autonomous
  actions: https://support.google.com/googleplay/android-developer/answer/16550159
