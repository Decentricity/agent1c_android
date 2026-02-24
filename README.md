# Agent1c Android (Hitomi Overlay)

Native Android floating Hitomi ("talking hedgehog") companion app for the Agent1c ecosystem.

This repo is the Android client prototype / app codebase.

Related projects:
- `agent1c.ai` (cloud-hosted Agent1c OS)
- `agent1c.me` (local-first / BYOK Agent1c OS)

## Download APK (Current)

Release `0.0.1`:
- `releases/0.0.1/agent1c-hitomi-android-v0.0.1-debug.apk`
- checksum: `releases/0.0.1/SHA256SUMS.txt`
- notes: `releases/0.0.1/RELEASE_NOTES.md`

## What 0.0.1 includes

- Floating Hitomi hedgehog overlay (draggable)
- Clippy-style chat bubble with tail
- Supabase login (web-first handoff to app)
- Cloud chat via Agent1c.ai Supabase/xAI backend
- Android BeOS/HedgeyOS-inspired main screen styling

## Notes

- This is an early prototype release.
- We plan to publish through F-Droid later; for now, direct APK testing is the intended path.
- App signing / release builds can be added after the auth + overlay UX stabilizes further.
