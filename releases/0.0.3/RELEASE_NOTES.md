# Agent1c Android v0.0.3

## Highlights

- Hitomi Browser Phase 2 + Phase 3 (Android overlay mini browser)
  - Hidden by default; Hitomi can show it while browsing
  - Visible mini BeOS/HedgeyOS-style browser window next to Hitomi
  - URL loads inside the mini browser
  - Page title + visible text excerpt can be extracted for Hitomi follow-up replies
- Android browser tool tokens wired into chat flow
  - `android_browser_open` (show/load browser only)
  - `android_browser_browse` (show/load + read visible page excerpt)
- Android prompt tuning
  - `TOOLS.md` updated for Android browser behaviors
  - `SOUL.md` updated so Hitomi uses `fren` and keeps replies to max 2 sentences when possible
- Overlay polish retained from v0.0.2
  - STT always-listening
  - radial actions
  - hide-to-edge and edge arc restore
  - keyboard avoidance improvements

## Notes

- This is still a debug APK prototype build.
- Android mini browser page ingestion is visible-text excerpt based (not full tool/browser automation yet).
