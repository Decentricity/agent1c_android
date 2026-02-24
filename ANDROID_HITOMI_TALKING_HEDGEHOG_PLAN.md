# Agent1c Android Plan: Hitomi "Talking Hedgehog"

## Goal

Build an Android version of Hitomi that behaves like a Messenger-style floating "talking head":

- A draggable hedgehog overlay that sits above other apps
- Tap to open a small chat bubble
- Long-press / hold for push-to-talk
- Full app view available when needed

This should feel like Hitomi lives on the phone, not just inside a browser tab.

## Product Positioning

### Primary target (first)

- `agent1c.ai` users (managed cloud, login-based)
- Fast onboarding
- Minimal setup friction

### Secondary target (later)

- `agent1c.me` users (sovereign / local-first)
- Optional advanced local relay / Tor relay support on Android

## Core UX (MVP)

### 1. Floating Hedgehog Overlay

- Small draggable Hitomi hedgehog appears over the Android desktop/apps
- Snaps to screen edge when released
- Avoids status bar / gesture bar zones
- Visual states:
  - Idle
  - Listening (push-to-talk held)
  - Thinking (waiting for reply)
  - New message notification pulse

### 2. Mini Chat Bubble

Tap hedgehog opens a compact dialog bubble (Messenger/Clippy style):

- Shows last messages
- Text input + Send
- Optional quick reply chips
- Close bubble by tapping outside
- Hedgehog remains floating

### 3. Full-Screen Setup / Login Screen (first-run + maintenance only)

The Android app should **not** expose a full desktop/web-OS UI in normal use.

Full-screen UI is used only for:

- first login / signup (Supabase)
- permissions onboarding (overlay + mic)
- account settings / sign out
- credits / subscription management
- debugging / recovery (optional)

After setup/login completes, users should be able to close the screen and use only the floating hedgehog.

### 4. Push-to-Talk

Long-press on hedgehog:

- Starts speech recognition capture
- Shows live transcript near hedgehog/bubble
- Releases after ~250ms grace window to avoid cutting the final word
- Sends final transcript to Hitomi

This maps well to the current desktop Hitomi UX and avoids always-listening complexity in MVP.

## Technical Architecture (Recommended)

## A. Native overlay-first app + hidden web runtime (Hybrid model)

This is the recommended approach.

### Why hybrid?

- Native Android is best for:
  - overlays
  - foreground service
  - notifications
  - permissions
  - drag/touch handling
  - push-to-talk UX
- Web runtime is best for:
  - existing Agent1c UI/chat logic
  - fast iteration
  - shared behavior with browser versions

### Hybrid split (overlay-first)

- Native app:
  - floating hedgehog overlay + mini bubble UI (primary UX)
  - first-run full-screen login/setup UI
  - permission flow (overlay, mic)
  - audio capture / STT
  - lifecycle
  - notifications
- Embedded **hidden runtime WebView** (preferred) or lightweight visible maintenance WebView:
  - Agent1c runtime (`agent1c.ai` / `app.agent1c.ai` auth/session + chat logic)
  - chat/thread/context logic
  - Hitomi behavior
  - managed-cloud integrations (credits, Telegram linking, xAI managed provider path)

Key product rule:

- The floating hedgehog + mini bubble is the **main interface**.
- The full-screen screen is setup/settings only.

## B. Foreground Service (Required)

Use a foreground service to keep the overlay alive and stable.

Responsibilities:

- Own floating hedgehog lifecycle
- Handle overlay window attach/detach
- Manage session state (online/offline)
- Coordinate with WebView runtime
- Surface a persistent notification ("Hitomi is ready")

Why required:

- Android kills background processes aggressively
- Overlay + quick response UX needs a persistent process anchor

## C. Overlay Window Implementation

Use `WindowManager` with overlay window params.

### Permissions

- `SYSTEM_ALERT_WINDOW` (overlay permission)
- `RECORD_AUDIO` (for push-to-talk)
- Foreground service permissions (Android version dependent)

### UI Layers

1. Hedgehog overlay view (always visible when enabled)
2. Bubble overlay view (shown on tap)
3. Optional transcript chip overlay near hedgehog

### Interaction rules

- Tap: toggle mini bubble
- Long press: push-to-talk
- Drag: move hedgehog
- Snap to edge on release
- Prevent accidental drag on transparent pixels (respect image hit-shape if possible; fallback to bounded touch area)

## D. Web Runtime Integration (WebView Bridge)

### MVP runtime approach

Start with `agent1c.ai` cloud chat via authenticated web session.

Options:

1. Visible WebView full app + native overlay (simpler prototype)
2. Hidden/background WebView runtime for chat only + native mini UI (better product UX)

Recommended MVP:

- Use a hidden/background WebView runtime as the source of truth for Hitomi state
- Keep a small full-screen setup/settings activity that can host a visible WebView only when needed
- For mini bubble interactions, call into runtime WebView JS bridge methods
- Keep one source of truth for chat state in the web runtime to avoid logic duplication

### Bridge API (example)

Native -> Web:

- `sendMessage(text)`
- `startPushToTalkTranscript(partialText)`
- `finishPushToTalkTranscript(finalText)`
- `getUnreadState()`
- `getSessionState()`
- `openSettingsScreen(section?)`

Web -> Native:

- `onReplyStarted`
- `onReplyCompleted(textPreview)`
- `onUnreadChanged(count)`
- `onNeedsLogin`
- `onTokenLimitReached`
- `onOpenExternalAuth(url)` (Google/X OAuth via custom tab/browser)
- `onLinkStateChanged(kind, payload)` (Telegram link, credits, etc.)

This avoids duplicating chat logic in Kotlin while still giving native overlay UX.

## E. Speech Recognition (MVP)

### MVP mode

- Push-to-talk only
- Android SpeechRecognizer (system STT)
- No wake-word in first version

Why:

- Wake-word on Android is much more complex + battery sensitive
- OEM restrictions make background listening unreliable
- PTT fits overlay UX and user expectations

### Later modes

- Wake-word mode (optional)
- Always-listening mode (dangerous for battery/privacy; advanced only)

## F. Notifications (Required backup path)

If overlay is disabled or killed:

- Hitomi can still send Android notifications
- Tap notification opens full app or bubble

This improves reliability when OEM background policies get aggressive.

## G. Account / Auth model (`agent1c.ai` first)

Use the existing `agent1c.ai` auth stack (Supabase):

- Google
- X
- Magic link

### Important mobile UX notes

- OAuth opens browser tabs / custom tabs (not inside a brittle embedded flow)
- On return, app resumes and reconnects session
- The app should skip setup/login UI if a valid session already exists and go straight to overlay service startup

### Android login/setup flow (overlay-first)

1. First launch opens full-screen setup/login activity
2. User signs in with Google / X / Magic Link (same Supabase infra as web)
3. App requests overlay permission and microphone permission
4. User taps `Start Hitomi`
5. Foreground service starts + floating hedgehog appears
6. Full-screen activity can be closed; hedgehog remains primary UI

## H. `.ai` vs `.me` support strategy

### Phase 1 (ship first)

- `agent1c.ai` only
- Managed cloud chat
- Credits/settings screen in native setup/settings UI
- Telegram link flow launched from native setup/settings UI (or mini bubble shortcut)

### Phase 2

- `agent1c.me` compatibility mode inside Android app/WebView
- Optional local relay / Tor relay setup docs
- Clear warning that `.me` may require more user setup

### Phase 3

- Native relay helpers for Android (if desired)
- Local on-device model integrations (advanced)

## Permissions & Trust UX (Very important)

We should be explicit and friendly.

### First-run onboarding permissions

1. Explain overlay permission
   - "Lets Hitomi float above apps like a talking hedgehog"
2. Explain microphone permission
   - "Used for push-to-talk only"
3. Explain privacy caveat
   - Speech recognition may use vendor services depending on Android/browser/system components

### User controls

- Toggle overlay on/off
- Toggle microphone on/off
- Toggle push-to-talk hints
- Battery optimization guidance link

## MVP Feature Checklist

### Must-have

- Floating hedgehog overlay
- Tap-to-open mini bubble
- Text chat input/output
- Push-to-talk
- Foreground service
- Full-screen setup/login/settings screen
- Auth flow (`agent1c.ai`)
- Notification fallback

### Nice-to-have (but can wait)

- Hops/idle animations matching desktop
- Storybook idle lines
- Emoji/sound effects
- Edge docking styles

## Risks / Gotchas

### 1. OEM battery/background killing

Mitigation:
- foreground service
- user guidance for battery optimization exclusions
- notification fallback

### 2. Overlay permission friction

Mitigation:
- clear value-driven explanation
- defer permission request until user opts in

### 3. STT variability across devices

Mitigation:
- push-to-talk only MVP
- visible transcript preview
- resend/edit before send option (optional)

### 4. Play Store policy sensitivity

Mitigation:
- avoid deceptive overlays
- explicit user controls
- no hidden accessibility automation in MVP

## Suggested Implementation Phases

## Phase A: Native overlay shell prototype

- Floating hedgehog
- Drag/snap
- Tap to dummy bubble
- Foreground service

## Phase B: Supabase login/setup screen (`agent1c.ai`)

- Full-screen login/setup activity
- Supabase auth integration (Google / X / Magic Link)
- Session persistence + resume
- Overlay + mic permission onboarding

## Phase C: Hidden runtime bridge (`agent1c.ai`)

- Hidden/background WebView runtime
- JS bridge for send/receive messages
- Mini bubble chat wired to runtime
- Basic reconnect/session-state handling

## Phase D: Push-to-talk

- Long press on hedgehog
- STT transcript preview
- Send to Hitomi via bridge

## Phase E: Polish + reliability

- Notifications
- battery guidance
- reconnect/session handling
- bubble/hedgehog animation polish

## Phase F: Advanced features (later)

- Wake-word mode
- Accessibility-powered agent actions (explicit advanced mode)
- `.me` relay/Tor UX on Android

## Engineering Recommendation (summary)

Build this as an **overlay-first native Android app** with a hidden hybrid WebView-backed Hitomi runtime.

That gives us:

- Messenger-style UX quality
- Hitomi as the primary product surface (not "a web app in an app")
- reuse of existing Agent1c cloud/runtime logic
- faster iteration
- a clean path to deeper Android-native integration later

---

## Current Agent1c.ai Supabase / Backend Details (for future implementation)

This section is intentionally concrete so a future instance can continue implementation quickly.

### Supabase project (Agent1c.ai)

- Project ID: `gkfhxhrleuauhnuewfmw`
- Base URL: `https://gkfhxhrleuauhnuewfmw.supabase.co`
- Publishable key:
  - `sb_publishable_r_NH0OEY5Y6rNy9rzPu1NQ_PYGZs5Nj`
- Direct Postgres connection string (password placeholder intentionally preserved):
  - `postgresql://postgres:[YOUR-PASSWORD]@db.gkfhxhrleuauhnuewfmw.supabase.co:5432/postgres`

### Existing auth/providers in use on web

- Google OAuth
- X (Twitter) OAuth
- Magic link email

These are already wired and working in the web app and should be reused for Android.

### Current domain / redirect context (web)

- `https://agent1c.ai`
- `https://app.agent1c.ai`

Android implementation note:

- Add Android app deep-link redirect URI(s) in Supabase auth config
- Keep existing web redirect URIs; Android should be additive, not replacing web

### Existing cloud function endpoint(s)

- xAI chat edge function:
  - `https://gkfhxhrleuauhnuewfmw.supabase.co/functions/v1/xai-chat`

### Existing / planned Edge Functions relevant to Android parity

- `xai-chat` (managed cloud chat + quota enforcement path)
- `xai-usage` (usage/credits helper path; verify current usage before relying on it)
- Telegram cloud relay function set (used/planned by web and should be reused conceptually by Android):
  - `telegram-link-init`
  - `telegram-link-status`
  - `telegram-webhook`
  - online-session heartbeat / inbox relay helpers (naming may vary; inspect current web implementation before coding)

### Critical operational guard (do not forget)

For Supabase Edge Function `xai-chat`:

- `Verify JWT with legacy secret` **MUST remain OFF**
- If this gets turned ON, chat requests can fail with `401`
- This is a known failure mode in this project

### Existing product behaviors to preserve in Android

- Managed cloud chat (`agent1c.ai`) uses server-side xAI integration, not client-held API keys
- Per-user token accounting is server-side (Supabase), not xAI-side usage
- Telegram cloud relay model is tab/session-online aware in web; Android should eventually provide equivalent "active session" semantics via foreground service + runtime heartbeat

### Token accounting note (important)

- Android client must not compute token usage itself
- Display credits by calling the same backend source used by web Credits UI
- Known bug class in this project:
  - stale daily bucket rows can survive rollover if backend bucket filtering is wrong
  - fix belongs in Supabase edge/backend logic, not client UI

### Future Android auth implementation note

Best approach:

- Use native auth launcher / browser custom tabs for OAuth sign-in
- Reuse Supabase session in app storage
- Pass session into runtime WebView (or let WebView share session through controlled handoff)
- Avoid brittle in-WebView OAuth popups as the primary path

### Future-instance implementation handoff checklist (Android + existing web stack)

Before coding Android auth/runtime integration, inspect:

- `agent1c-ai.github.io/js/agent1cauth.js`
- `agent1c-ai.github.io/AGENTS.md` (Supabase guard + token accounting sections)
- `agent1c-ai.github.io/supabase/OPERATIONS_CHECKLIST.md`
- Current cloud credits window logic and cloud Telegram link window logic in `js/agent1c.js` (or extracted modules if refactor progresses)

### Security note (temporary secrets in docs)

This file is a local working plan. If any temporary secrets/tokens are added here during development, rotate them after the development cycle and remove them from committed docs.

## Future follow-up doc ideas

- `ANDROID_OVERLAY_PERMISSION_FLOW.md`
- `ANDROID_WEBVIEW_BRIDGE_CONTRACT.md`
- `ANDROID_STT_PTT_SPEC.md`
- `ANDROID_ACCESSIBILITY_ADVANCED_MODE.md`
