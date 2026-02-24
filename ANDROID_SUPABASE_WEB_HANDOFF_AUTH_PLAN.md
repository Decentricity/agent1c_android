# Android Auth Handoff Plan (Agent1c Hitomi Overlay)

## Goal

Implement a clean Android login flow for the native Hitomi overlay app that:

- reuses the already-working `agent1c.ai` web OAuth flow (especially X)
- returns the authenticated session back to the Android app
- avoids provider-specific Android OAuth quirks in the native app
- keeps the Android app overlay-first (full-screen UI only for setup/login/settings)

This document is the implementation contract before coding the handoff.

## Why this plan

Current status:

- Google + Magic Link can work in the Android app with direct Supabase callback flow.
- X login is unreliable on Android because the X app hijacks the browser auth flow and lands on X home instead of finishing OAuth.
- The `agent1c.ai` web login window already works for X reliably.

Therefore, the clean approach is:

- let the Android app open the `agent1c.ai` web login flow in a special "Android auth mode"
- after successful web login, the web app creates a short-lived one-time handoff ticket in Supabase
- the web app deep-links back to the Android app with that ticket
- the Android app exchanges the ticket for a Supabase session (access + refresh)

## Architecture (Option B: One-time handoff ticket)

### Components

1. **Android app (native)**
- Opens a browser tab to `agent1c.ai` with an Android-auth query flag.
- Receives deep-link callback `agent1cai://auth/callback?handoff_code=...`.
- Exchanges handoff code via Supabase edge function.
- Stores session using existing `SupabaseAuthManager`.

2. **Agent1c.ai web app**
- Detects `android_auth=1` (or similar flag).
- Opens the existing login window directly (skip Intro for this flow).
- After successful login, calls a new edge function to create a handoff code.
- Deep-links back to Android app with the handoff code.

3. **Supabase Edge Function** (new)
- `android-auth-handoff`
- Authenticated endpoint for handoff creation and exchange.
- Creates one-time code bound to user/session.
- Exchanges code for a minimal session payload.

## Handoff flow (end-to-end)

### A. Android -> Web login

Android app launches:

- `https://agent1c.ai/?android_auth=1`

Optional future parameters:

- `platform=android`
- `return_scheme=agent1cai://auth/callback`
- `app_version=...`

### B. Web login (existing Agent1c.ai login window)

When `android_auth=1` is present:

- force-open the Sign In window after load
- suppress non-essential onboarding surfaces (intro/preload can remain minimal if desired)
- user logs in using web OAuth (X/Google/Magic Link)

### C. Web creates handoff ticket

After web login success:

- web app calls `android-auth-handoff` edge function with bearer auth
- edge function verifies authenticated user
- edge function creates:
  - `handoff_code` (random, one-time)
  - `expires_at` (short TTL, e.g. 60-120 seconds)
  - bound `user_id`
  - bound `session metadata` (optional)

Edge function returns:

- `handoff_code`
- `expires_in`

### D. Web deep-links to Android

Web app redirects browser to:

- `agent1cai://auth/callback?handoff_code=<code>`

If app is unavailable:

- show fallback message and retry button

### E. Android exchanges handoff code

Android app receives deep link and calls `android-auth-handoff` edge function:

- `POST /functions/v1/android-auth-handoff`
- body: `{ "handoff_code": "..." }`

Edge function validates:

- code exists
- not expired
- not used
- marks code as used atomically

Edge function returns:

- Supabase session payload (access token, refresh token, expires_in)
- user profile basics (email/provider/display if convenient)

Android stores session via existing `SupabaseAuthManager`, refreshes profile, and returns to overlay-ready state.

## Supabase implementation details

### Project

- Project ID: `gkfhxhrleuauhnuewfmw`
- Base URL: `https://gkfhxhrleuauhnuewfmw.supabase.co`
- Publishable key: `sb_publishable_r_NH0OEY5Y6rNy9rzPu1NQ_PYGZs5Nj`

### Existing function we must not break

- `xai-chat`

### Hard operational guard

- `xai-chat` -> `Verify JWT with legacy secret` must remain **OFF**
- Always verify after any Supabase changes

### New function

- `android-auth-handoff`

Recommended behavior:

#### Mode 1: create handoff code (authenticated)

- Requires valid bearer token from logged-in web app session
- Creates one-time code row

Request:

```json
{ "action": "create" }
```

Response:

```json
{
  "ok": true,
  "handoff_code": "abc123...",
  "expires_in": 90
}
```

#### Mode 2: exchange handoff code (unauthenticated or anon-key + code)

Request:

```json
{
  "action": "exchange",
  "handoff_code": "abc123..."
}
```

Response:

```json
{
  "ok": true,
  "session": {
    "access_token": "...",
    "refresh_token": "...",
    "expires_in": 3600,
    "token_type": "bearer"
  },
  "user": {
    "id": "...",
    "email": "...",
    "provider": "x"
  }
}
```

### Storage table (recommended)

Create a table like `android_auth_handoffs`:

- `code` (text, PK)
- `user_id` (uuid)
- `created_at` (timestamptz)
- `expires_at` (timestamptz)
- `used_at` (timestamptz nullable)
- `used_by` (text nullable, optional device info)

Rules:

- TTL short (60-120s)
- one-time use only
- mark used atomically on exchange

## Agent1c.ai web app changes (planned)

### Detect Android auth mode

On load:

- parse `android_auth=1`
- set `appState.androidAuthMode = true`

### Android auth mode behavior

- open/focus existing Sign In window immediately
- skip intro-window blocking behavior for this path
- after user becomes authenticated, run handoff sequence

### Handoff sequence in web app

1. Call `android-auth-handoff` with `action=create`
2. If success:
   - navigate to `agent1cai://auth/callback?handoff_code=...`
3. If deep-link fails:
   - show a small instruction window/button: "Return to Hitomi app"

## Android app changes (planned)

### MainActivity deep-link handling

Extend current callback handling:

- if URI has `handoff_code`, call `exchangeHandoffCode(...)`
- persist returned session through `SupabaseAuthManager`
- refresh user profile and UI

### SupabaseAuthManager additions

- `exchangeAndroidHandoffCode(String code)`
- `storeSessionFromJson(JSONObject session)`

No change needed to overlay chat path once session is stored.

## Security considerations

- One-time handoff code only (single-use)
- Very short TTL
- Do not log full tokens in app or web console
- Prefer deep-link with handoff code, not raw tokens
- Web app must require authenticated user before creating code

## Why this is cleaner than native provider OAuth in-app (for now)

- Reuses web login UX that already works for X
- Avoids Android/X app link hijack complexity in native app
- Keeps Android app simple and overlay-focused
- Centralizes provider login complexity in one place (`agent1c.ai`)

## Rollback / safety plan

Before implementing this:

- Android app is now pushed as its own repo under Decentricity (this repo)
- If handoff implementation regresses auth, revert Android app repo to last known-good commit
- Web app and Supabase changes can be reverted independently

## Implementation order (safe path)

1. Revert Android AppAuth experiment (restore current working Google/Magic baseline if not already reverted)
2. Add Supabase `android-auth-handoff` function + table
3. Add `android_auth=1` mode to `agent1c.ai` web app login flow
4. Add Android deep-link handoff code exchange
5. End-to-end test with X (primary target), then Google, then Magic Link

## Success criteria

- User taps "Continue with X" in Android app
- Web auth flow completes in browser using working `agent1c.ai` login window
- Browser returns to app automatically
- Android app shows signed-in status
- Floating Hitomi can chat using cloud backend immediately

