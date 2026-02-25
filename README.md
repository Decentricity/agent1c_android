# Agent1c Android (Hitomi Overlay)

![Hitomi Hedgehog](app/src/main/res/drawable/hedgey1.png)

Native Android floating Hitomi ("talking hedgehog") companion app for the Agent1c ecosystem.

This repo is the Android client prototype / app codebase.

Related projects:
- `agent1c.ai` (cloud-hosted Agent1c OS)
- `agent1c.me` (local-first / BYOK Agent1c OS)

## Download APK (Current)

Release `0.0.2`:
- Direct APK download: https://github.com/Decentricity/agent1c_android/raw/refs/tags/v0.0.2/releases/0.0.2/agent1c-hitomi-android-v0.0.2-debug.apk
- `releases/0.0.2/agent1c-hitomi-android-v0.0.2-debug.apk`
- checksum: `releases/0.0.2/SHA256SUMS.txt`
- notes: `releases/0.0.2/RELEASE_NOTES.md`

## What 0.0.2 includes

- Floating Hitomi hedgehog overlay (draggable)
- Clippy-style chat bubble with tail
- Supabase login (web-first handoff to app)
- Cloud chat via Agent1c.ai Supabase/xAI backend
- Android BeOS/HedgeyOS-inspired main screen styling
- Long-press radial quick actions (settings, mic, hide-to-edge)
- Native Android STT always-listening mode
- Hide-to-edge arc tab restore interaction

## Notes

- This is an early prototype release.
- We plan to publish through F-Droid later; for now, direct APK testing is the intended path.
- App signing / release builds can be added after the auth + overlay UX stabilizes further.
