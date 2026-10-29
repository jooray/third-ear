# Third Ear — plan and status

Third Ear is a voice recorder for everyone, a permanent fork of
[Fossify Voice Recorder](https://github.com/FossifyOrg/Voice-Recorder) (`upstream` remote, kept for
cherry-picking fixes; never merged back). It adds the recording, transcription and White Noise work
from Juraj's I Hear You (`~/projects/i-hear-you`) and his OSTT fork
(`~/projects/ostt-work/ostt-combined`).

This file is the resumable plan: if work stops, read the checklist at the bottom and continue from
the first unchecked item. Commits are local only (never pushed).

## Decisions

| Topic | Decision | Why |
|---|---|---|
| Repo | `~/projects/third-ear`, branch `main` from `reliability/wake-lock-error-watchdog` (86bd3b5, 23fe711). Fossify's old `main` is kept as local branch `fossify-main`; remote `upstream` = FossifyOrg. | permanent fork, cherry-pick later |
| applicationId | `app.thirdear` (`app.thirdear.debug` for debug builds) | own identity, coexists with Fossify |
| Kotlin namespace | kept `org.fossify.voicerecorder`; new code in sub-packages (`audio`, `actions`, `pipeline`, `whitenoise`, `tiles`, `car`) | renaming touches every file and breaks cherry-picks from upstream; not cheap |
| minSdk | 31 (was 26) | `setCommunicationDevice` / `availableCommunicationDevices` (31), MediaMuxer OGG (29); OSTT caller-audio fallback needs 33 and is runtime-gated |
| License | GPLv3 (unchanged; I Hear You code is Juraj's own) | |
| fossify-commons | kept (look, dialogs, settings rows, storage helpers) | |
| commons fork checks | commons skips its SAF permission dialog when the package is not `org.fossify.*` (`handleSAFDialogSdk30` calls back `true` without asking). Third Ear calls commons' `isShowingSAFDialogSdk30` itself (`extensions/Activity.kt: handleSafSdk30`). Launcher colour aliases are named `app.thirdear.activities.SplashActivity.<Colour>` because commons' `appLaunched` builds the alias name from the app id. Commons' "fake version" notice in About/Customization may still appear — harmless, noted. | |
| Recording engine | own `AudioRecord` front end (phone `MIC`/user source, or Bluetooth through the communication route with `VOICE_COMMUNICATION`) → streaming `MediaCodec` encoder → append-only **frame journal** on app storage → container written at stop by the same finaliser that recovers a crashed recording (MediaMuxer MPEG-4 for AAC, OGG for Opus). MP3 (LAME) is itself crash-safe and written directly. Fossify's `MediaRecorderWrapper`/`Mp3Recorder` are replaced. | long recordings, crash-safe, a source switch continues into the same file |
| Sample rate | the encoder's rate is fixed per recording (user's setting); `AudioRecord` is opened at that rate for both sources and Android resamples Bluetooth input | a source switch cannot change the file's format |
| Transcription | OSTT file API (`SpeechRecognizer` headless, `app.offlinespeechtotext.extra.AUDIO_URI`, our FileProvider `content:` Uri granted to the package), fallback: Android 13 caller audio (`EXTRA_AUDIO_SOURCE`, the file decoded and resampled to 16 kHz mono PCM16). Busy → retry 5, 15, 30, 60 s, then every 2 min (I Hear You's schedule). | |
| White Noise | MarmotKit 0.14.0, fetched by `tools/marmotkit/fetch.sh` (sha256-pinned, git-ignored), Third Ear's own identity; arm64 only — on other ABIs the section says it is unavailable | |
| Android Auto | Car App Library, category IOT (grid of actions; sideloaded apps need AA developer settings → Unknown sources), plus MessagingStyle notifications with Reply and Mark-as-read | optional, the app fully works without it |

## Architecture (new code)

```
audio/      PcmSource (PhoneSource, BluetoothSource+BluetoothRoute+SilencePrimer), ZeroRunDetector,
            SourcePolicy (pure: which source, fallback once, follow disconnects),
            RecordingEngine (thread: read source → sink; pause; switch source mid-file),
            AudioSink (AacSink/OpusSink via MediaCodec → FrameJournal; Mp3Sink), Finaliser
actions/    RecordAction, Destination, Content, ActionStore (JSON prefs), ActionPlan (pure routing)
pipeline/   NotePipeline (finalise → export → transcribe → deliver; progress notification),
            Transcriber (file API + caller-audio fallback), Retry (backoff), Webhook, Cues
            (I Hear You's voice clips + synth tones), NoteStore (recent notes, retry), MediaPauser
whitenoise/ MarmotHost, SecureKv, GroupSettings + ReplyPolicy (pure), IncomingStore, ReplySpeaker,
            MessageNotifier (MessagingStyle, Reply, Mark read, Read aloud), receivers
tiles/      ActionTile1..3 (Quick Settings), bound to configured actions
car/        ThirdEarCarAppService, ActionsScreen, RecordingScreen, CarModel (pure)
```

UI (Fossify views + commons): the recorder tab shows timer, level meter, the **source button**
(verified input; "Waiting for the Bluetooth microphone…"), and the configured actions as big
buttons. Idle: tap = start recording with that action. Recording: every action button reads
"Stop → <name>" with a one-line description of what will happen; tap = stop and run that action
(the starting one is highlighted). Pause and discard stay. A "last recording" card shows progress,
transcript (copy), retry. Settings gain: Bluetooth microphone, actions, tiles, transcription,
spoken cues, pause media, White Noise (own screen), Android Auto notes.

## Status checklist

- [x] M0 Move repo to `~/projects/third-ear`, `upstream` remote, `main` branch, plan file
- [x] M0 Rename: app id, name, minSdk 31, commons fork workarounds, version 0.1.0 — builds
- [x] M1 Recording engine (phone source, AAC/Opus/MP3 sinks, frame journal, finaliser, recovery), new RecorderService, tests
- [x] M2 Bluetooth source (route, primer, first-sound wait, fallback once with cue, follow disconnects), source button + switch dialog, setting, tests
- [x] M3 Actions (model, store, editor UI), recorder screen with action buttons, stop-with-destination, tests
- [x] M4 Pipeline: export, transcription (file API + fallback + retry), progress notification, cues, clipboard, share, webhook, last-recording card, copy/transcribe in player, tests
- [x] M5 Quick Settings tiles (3 slots), widget uses default action, boot/update receiver
- [x] M6 White Noise: MarmotKit fetch, host, identity, npub (copy + QR), invites, groups, send voice/text, nsec export/import
- [x] M7 Replies: per-group settings, ReplyPolicy, speaker, recent messages screen, MessagingStyle notifications with Reply / Mark read / Read, tests
- [x] M8 Android Auto: car app screens, CarConnection → ask, tests, DHU if available
- [ ] M9 Slovak strings, README, testing guide, lint, device verification

## Notes while building

(append findings here)
