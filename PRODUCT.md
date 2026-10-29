# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

The Android app is the platform of record. The static site in `website/` (GitHub Pages, en/sk/cs/de/es) is the product's web surface and follows web conventions.

## Users

Two overlapping core groups; "a voice recorder for everyone" is the reach, not the design target.

- **Power users and tinkerers.** Privacy-minded people who sideload, use Zapstore and nostr, and want a recorder they can wire up: their own action buttons, webhooks, White Noise groups, AI agents behind the MDK connector.
- **Voice-first, mostly hands-free users.** Headset and LE Audio hearing-aid wearers, drivers, people talking to an agent. Recording starts and stops with a tap (buttons, Quick Settings tiles, widget, Android Auto grid); everything after the stop (transcribing, sending, replies read aloud) happens without looking at the screen. The sister app I Hear You (`~/projects/i-hear-you`) covers the fully hands-free case from the hearing-aid buttons.

## Product Purpose

A voice recorder that also does something with what you said: records reliably (also long and from Bluetooth microphones), transcribes on the phone, and delivers the recording or transcript where the tapped action says: save, clipboard, share, webhook, or a White Noise group. Replies from White Noise come back as played voice or text read aloud, also in the car. Success: you speak, the words land in the right place, and any answer reaches your ears, with no fiddling after the stop.

## Positioning

- **The destination is chosen at the moment you stop.** Every action button reads "Stop → name" while recording, so one tap at the stop still picks where it goes.
- **Bluetooth microphones done right**: headsets, LE Audio hearing aids, the car's hands-free mic, with the real input shown, a mid-recording switch into the same file, and one audible fallback to the phone.
- **Transcription stays on the phone** through Offline Speech to Text (OSTT). Third Ear has no speech engine of its own.
- **The loop closes in White Noise**: speak, an agent (e.g. the Hermes agent) gets the transcript or audio, its answer is played or read back.

## Operating Context

- Phone in a pocket or on a mount; headset, hearing aids or car hands-free; screen often off or locked during transcription and replies.
- Android Auto (optional, sideloaded: needs AA developer settings → Unknown sources): action grid, recording screen with "Stop → action" rows, recent messages, messaging notifications with Reply / Mark as read. Nothing plays automatically in the car.
- Companion apps: Offline Speech to Text (Juraj's fork `app.offlinespeechtotext` with the file API, or stock OSTT on Android 13+), White Noise groups (MarmotKit), I Hear You.
- Distribution: Zapstore (not published yet: "Coming soon to Zapstore"), source on GitHub.

## Capabilities and Constraints

- Android 12+ (minSdk 31). White Noise needs a 64-bit ARM phone and network.
- Permanent fork of Fossify Voice Recorder. The UI is Third Ear's own (Jetpack Compose: Board, Log, Lines, Setup); fossify-commons stays only as a helper library (storage access, permissions, recycle-bin file moves). Kotlin package and app id `app.thirdear`.
- Recording: own `AudioRecord` front end, AAC/.m4a, Opus/.ogg, MP3, crash-safe frame journal (a killed process loses at most the last second), wake lock, silence watchdog.
- Actions: name + destination (save, copy transcript, share, White Noise audio/transcript/both, webhook) + transcribe on/off. Three Quick Settings tiles and a home-screen widget bound to actions. Progress notification, optionally spoken (spoken cues in Lisa's voice, from I Hear You).
- White Noise: own npub (copy, QR), accept invites, nsec export/import; per-group reply policy (off / play voice / play voice and read text; automatic or on request), ignore list (default 🎙️).
- Terminology: "line" in the UI (an action as a key on the Board, with a cord colour and a line code), "action" in code and docs, "Stop → <line>", "source button", "Offline Speech to Text (OSTT)", "White Noise", "group".
- UI languages: English, Slovak, Czech, German, Spanish (app and website).
- Undecided: final GitHub, Zapstore and I Hear You site URLs (marked `<!-- TODO -->` in `website/`).

## Brand Commitments

- Name: Third Ear. Made by Juraj Bednár. GPLv3, no account, no analytics.
- Voice: plain, factual, concrete about what happens; says what is verified and what is not.
- Visual reference the user made binding: Third Ear may follow I Hear You's design (`~/projects/i-hear-you/DESIGN.md`).
- Existing assets: app icon/logo mark (inline SVG in `website/index.html`), `graphics/`, website screenshots in `website/img/`.

## Evidence on Hand

- Hardware verification on a Pixel with LE Audio hearing aids (screen-off/locked continuity, formats, Bluetooth path, OSTT file API, White Noise, signed release): `docs/TESTING.md`, `docs/THIRD-EAR-PLAN.md`.
- Emulator screenshots: `website/img/` (recorder, recording, actions, action editor, notification, settings, White Noise, group settings).
- No users, testimonials, press, download counts or store listing yet. Do not invent them.

## Product Principles

1. **Decide at the stop, not before.** Where a recording goes is a choice made in one tap at the end.
2. **Never lose the words.** Reliability (crash recovery, mic fallback, retries) beats features.
3. **Eyes-free after the tap.** Status and answers reach the ear (cues, spoken progress, read-aloud), not only the screen.
4. **Local and owned.** On-device transcription, no account, no analytics, own keys.
5. **Wireable, but plain by default.** Power features (webhooks, groups, agents) stay out of the way of a simple record-and-play.

## Accessibility & Inclusion

Hearing-aid users are a core audience: audio output and cues must work through hearing-aid Bluetooth, and nothing important should be audio-only or screen-only. Hands-free and in-car use require large, clear tap targets and glanceable states.
