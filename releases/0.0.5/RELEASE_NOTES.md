# Agent1c Android Hitomi v0.0.5

## Highlights

- Web-first login flow polish
  - single `Login` entry point in the Android app
  - stronger web handoff callback handling (deduped handoff code consumption)
  - Google/X sign-in via Agent1c.ai login window flow retained
- Overlay-first startup flow
  - when already signed in, app starts directly into Hitomi overlay (main screen stays out of the way)
- BeOS/HedgeyOS UI polish on Android main screen
  - pill-style window title chrome
  - decorative window buttons
  - conditional login/logout and start/stop button visibility
  - pastel pink `Start Hitomi Overlay` button
- Hitomi bubble UX polish
  - rounded bubble + sharp tail
  - adaptive above/below bubble placement
  - tail tracks Hitomi horizontally when clamped
  - transcript scrolling + autoscroll
  - keyboard overlap avoidance improvements (including many edge cases)
- Long-press quick actions (radial menu) polished
  - settings / mic / hide-to-edge actions
  - improved hitboxes, colors, animations
  - hide-to-edge with edge arc restore tab
- Native Android STT always-listening
  - persistent `Listening...` indicator while mic mode is on
  - pinned mic toggle visible beside Hitomi while listening
- Hitomi Browser upgrades (Phase 2/3 + polish)
  - hidden by default, summoned by Hitomi tool use
  - `android_browser_open` and `android_browser_browse`
  - visible stepped scrolling while Hitomi reads a page
  - page snapshot ingestion for follow-up answers
  - browser reuse-in-place on subsequent browsing (no teleporting)
  - magical summon animation + particle burst on first show
  - particle stream effect between Hitomi and browser during browse scroll
- Termux (superuser optional) T1/T1.5/T2
  - Termux detection + bridge availability status
  - `Enable Termux Shell Tools` helper flow
  - copyable setup command panel for `allow-external-apps=true`
  - manual command bridge test
  - Hitomi generic Termux shell tool support via `android_termux_exec`
  - Android `TOOLS.md` + `SOUL.md` updated for Termux and Android-local capabilities

## Notes

- This is still a debug APK prototype build.
- Termux shell tools are optional and intended for superusers.
- Browser page reading is visible-text snapshot based and not full browser automation yet.
