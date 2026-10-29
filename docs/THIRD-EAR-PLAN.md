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
- [x] M9 Slovak strings, README, testing guide, lint
- [ ] M9 Device verification on the Pixel (see "Device verification" below)

## Notes while building

- **Backup off.** `android:allowBackup="false"`: the White Noise secret is encrypted under a
  non-exportable Keystore key, so a restored copy could never be decrypted.
- **Foreground service types.** `RecorderService` is `microphone|dataSync`: the microphone type is
  kept through the pipeline after the stop, because the recognition service checks the caller's
  microphone access (while-in-use) during transcription. A retry from a notification may only get
  `dataSync`; then transcription can be refused ("open Third Ear once"). `WhiteNoiseService` is
  `remoteMessaging` (allowed from BOOT_COMPLETED / MY_PACKAGE_REPLACED).
- **Starting from the background.** Tiles and the widget start recording through the invisible
  `BackgroundRecordActivity` (Android 14+ only lets a visible app open the microphone). The car
  screen starts the service directly (the host binds with while-in-use capabilities); if Android
  refuses, the car shows "Open Third Ear on the phone once".
- **OSTT file API probe.** A provider gets `AUDIO_URI` only when its package exports the
  `app.offlinespeechtotext.action.TRANSCRIBE_AUDIO` activity; a provider that ignored the extra
  would listen to the microphone instead. Otherwise (Android 13+) the file is decoded and
  resampled to 16 kHz mono and streamed through `EXTRA_AUDIO_SOURCE`.
- **"Go ahead"** is played when the Bluetooth microphone delivers its first real sound, not at the
  press: on the phone microphone it would be recorded through the speaker, and before the first
  sound the user cannot be heard anyway.
- **Transcript files.** `.txt` next to the recording (setting, default on). Trash, restore and delete
  carry the `.txt` along (SAF sibling by document id); rename does not, and the in-app transcript
  (Copy transcript) is matched by the saved file name, so it is lost on rename. Small follow-up.
- **APK size.** The debug APK is ~120 MB: MarmotKit's 52 MB arm64 library (compressed to 25 MB)
  plus unminified dex of its 47k-line generated bindings. An unsigned release build (R8, keep
  rules for JNA/UniFFI in `proguard-rules.pro`) is 36 MB; it was not run on a device yet.
- **Not done / ideas:** a "Transcribe" menu item for recordings made before Third Ear (would run a
  recording through the pipeline); per-action spoken-cue toggle (it is global); webhook URL is per
  action; pause media needs notification access (Settings row opens the system screen).

- **commons vs forks, part 2** (found on the emulator): commons' `FilePickerDialog` OK and its
  "Create new folder" dialog also skip the SAF grant for non-`org.fossify` packages (SecurityException).
  First run now asks for the folder grant directly (commons' `isShowingSAFDialogSdk30`: system
  picker preset to the top-level folder, e.g. Music, then `Recordings` is created in it); "Save
  recordings in" uses the system folder picker (`SimpleActivity.pickFolder`) and then the same
  grant. Commons also shows "You are using a fake version of the app" at random (and on run 0,
  100, …) in `BaseSimpleActivity.onCreate`, and the nag instead of "Customize colors";
  `SimpleActivity` reports a fossify-prefixed package name to commons only during `super.onCreate`
  and the customization launch (`withCommonsIdentity`).

## Device verification

The Pixel (5B171FDCG000M1) was not connected during the session (only an unauthorized
JPAQ01064 showed up; nothing was installed there). Verified on the `Pixel_9` emulator
(Android 16, arm64, headless) instead, debug build:

- First run: folder grant through the system picker → `Music/Recordings` created; no nag.
- Recorder screen: actions as buttons, "Stop → …" while recording, the started one filled.
- Recording on the phone microphone → valid files: AAC-LC 48 kHz mono 96 kbps `.m4a` (14.1 s),
  Opus 48 kHz `.ogg` (5.0 s), MP3 48 kHz (5.1 s); non-zero samples (emulator input).
- Destination changed at the stop (started with Save, stopped with Transcript to clipboard): the
  pipeline transcribed through the caller-audio path (the emulator has Google's recognizer, not
  OSTT; it answered "no speech" for the emulator's near-silent input) and the card showed it.
- Foreground service types microphone|dataSync (0x81); notification "Recording · Save", input
  line, Pause and "Stop → Save".
- Crash safety: force-stop after 8 s → next launch recovered a valid 7.1 s `.m4a` and posted
  "Interrupted recording saved".
- Quick Settings tile 1 from the home screen: started recording via the trampoline, second tap
  stopped it.
- White Noise: identity created on enable (npub + QR), test group created, an action "Test group"
  (White Noise, audio) recorded and sent: note state DONE (uploaded and sent to the group that only
  Third Ear is in).

Still to verify on the Pixel: Bluetooth recording (aids/headset: route, waiting state, go-ahead,
non-zero samples, fallback cue, mid-recording switch), OSTT file API transcription with real
speech and the busy retry, tiles and widget on Android 17, incoming White Noise messages
(notification Reply / Mark as read / Read aloud, automatic vs ask), Android Auto (car screens,
CarConnection → ask, message announcements), pause-media with notification access.
