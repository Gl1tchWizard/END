---
title: END privacy policy
---

# Privacy policy for END

**Last updated: 4 September 2026**

END is made by WillSwitch. It makes infinite feeds finite: it counts how much
feed you scroll in the apps and sites you choose, and when your limit is
reached it stops the page until a countdown runs out.

## The short version

END collects nothing, stores nothing about you off your device, and has no way
to send anything anywhere. It has no Internet permission, so it cannot make a
network connection even if it wanted to. There is no account, no analytics, no
advertising, and no third party code of any kind in the app.

## What END is able to see

END runs as an Android accessibility service, which is the only way an app can
tell that a feed is scrolling. Android therefore makes several kinds of
information available to it. This is what END actually uses:

- **The package name of the app in the foreground.** Used to decide whether the
  app on screen is one you asked END to limit.
- **Scroll events.** Used to count how far a feed has moved. END reads the
  amount of movement, not what is on the screen.
- **Content change events.** Some apps never report scrolling, so END counts a
  dense burst of content changes as one screen instead. It reads that these
  events happened, not what changed.
- **That you touched the screen.** END keeps a one pixel window that Android
  notifies when a touch happens outside it. Android does not include
  coordinates in that notification, so END knows that you touched, never where.
  This is what keeps autoplay, refreshes and progress bars from counting as
  scrolling.
- **The browser address bar, only if you switch on browser feeds.** END reads
  one named field in the supported browsers, at most about once per second, and
  keeps only the host, for example `reddit.com`. It never keeps the path, the
  query or anything from the page, and it never reads page content.

## What END never does

- It never reads page text, messages, images, form fields or passwords.
- It never records what you looked at, for how long, or in what order.
- It never takes screenshots.
- It never intercepts, injects or redirects your touches.
- It never transmits anything. It cannot: the app declares no Internet
  permission.

## What is stored, and where

On your device only, in the app's private storage:

- which apps and sites you switched on;
- your limit and cooldown setting;
- how much feed has been used and when the current limit expires.

Nothing else. Uninstalling END deletes all of it. No copy exists anywhere else,
including with WillSwitch, because none was ever sent.

## Children

END is often installed by a parent on a family device. It is designed to work
the same either way and it collects no data about anyone, of any age.

## Verifying this

END is free software under the GNU General Public License version 3. The full
source is at <https://github.com/WillSwitch/END-Android>. Everything above can
be checked in the code, and you can build the app yourself and compare it to
the release we publish.

## Contact

WillSwitch, Groningen, Netherlands.
Questions or a privacy request: open an issue on the repository above.
