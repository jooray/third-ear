# Third Ear

A voice recorder for everyone that also does something with what you said: transcribe it on the
phone, copy the text, share it, post it to a webhook, or send it to a White Noise group — and
play the answers back to you, also in the car.

Third Ear is a permanent fork of [Fossify Voice Recorder](https://github.com/FossifyOrg/Voice-Recorder)
(GPLv3; its README is kept in [docs/FOSSIFY-README.md](docs/FOSSIFY-README.md)) with the
recording, transcription and White Noise work from I Hear You and the OSTT fork. The interface is
its own (Jetpack Compose): a **Board** of lines, a **Log** of recordings, replies and the recycle
bin, **Lines** to edit them and **Setup**. The design system is in [DESIGN.md](DESIGN.md).

## Features

- **Reliable long recordings.** Its own `AudioRecord` front end feeds a streaming encoder (AAC in
  `.m4a`, Opus in `.ogg`, MP3) through a crash-safe frame journal. A killed process loses at most
  the last second: the next launch finishes the file and saves it. Partial wake lock, silence
  watchdog (exact digital zeros, Android silencing the app); a failing microphone ends the
  recording and keeps what was captured.
- **Bluetooth microphones**: headsets, LE Audio hearing aids, the car's hands-free microphone.
  The source button on the recording screen shows the microphone that really records ("Waiting
  for the Bluetooth microphone…" until the first real sound). Tap it to switch; the recording
  continues in the same file. If the Bluetooth microphone does not come up within 8 s,
  disconnects or breaks, the recording falls back to the phone once, with an audible cue.
- **Lines (actions).** You define keys on the Board: a name, a cord colour, a short line code, plus where the recording goes (save only, copy the
  transcript, share, a White Noise group as audio, transcript or both, a webhook) and whether to
  transcribe. Tap one to start recording; while recording every key reads "Stop →" with
  what it will do, so one tap at the stop can still pick another destination. After the stop a
  notification says what happens (saving, transcribing, waiting for the transcription app,
  sending…), optionally also spoken. Each key has a lamp: amber while its last recording is
  transcribed or sent, white while a reply from its group waits, red when it was not delivered,
  always with a word beside it.
- **Transcription on the phone** through Offline Speech to Text (OSTT): the file API of Juraj's
  OSTT fork (`app.offlinespeechtotext`), or the Android 13 audio-stream contract of a stock OSTT.
  A busy OSTT (its keyboard dictating) is retried for about ten minutes. Transcripts are saved as
  `.txt` next to the recording and kept in the app: "Copy transcript" on the last recording and in
  the Log.
- **Quick Settings tiles**: three tiles, each bound to an action (start / stop). The home-screen
  widget is a line key for a chosen line.
- **White Noise** (MarmotKit 0.14.0): Third Ear gets its own npub (copy, QR). Accept group
  invites, send recordings as voice notes (transcript as caption when chosen) or text,
  export/import the nsec.
- **Replies, per group**: off / play voice messages / play voice and read text; automatically or
  on request ("Read aloud" in the notification or the message list); an ignore list (default 🎙️,
  the echo of your own transcript). Text is read with Android's text-to-speech.
- **Android Auto** (optional): a grid of your actions on the car screen, a recording screen with
  "Stop → action" rows and the microphone in use, the recent messages; incoming White Noise
  messages as car notifications with Reply and Mark as read, so Android Auto announces them and
  offers to read them. In the car nothing is played automatically.

## Requirements

- Android 12 or newer (minSdk 31).
- For transcription: Offline Speech to Text with a speech model. Juraj's fork
  (`app.offlinespeechtotext`, file API) is used first; a stock OSTT works on Android 13+.
- For White Noise: a 64-bit ARM phone (MarmotKit ships arm64 only) and network access.

## Android Auto setup

Third Ear is not from the Play Store, so Android Auto ignores it until you allow unknown sources:

1. Android Auto settings → tap **Version** ten times → allow **Developer settings**.
2. ⋮ → Developer settings → **Unknown sources** → on.
3. Android Auto settings → **Customize launcher** → make sure Third Ear is shown.
4. In Third Ear: Setup → **Use a Bluetooth microphone** on, so recordings use the car's
   microphone (hands-free over Bluetooth). Without it the phone's microphone records.

Android 14+ starts a microphone only for an app the user has in front; the car screen counts while
it is shown. After a reboot, open Third Ear on the phone once.

## Building

```sh
tools/marmotkit/fetch.sh                  # MarmotKit 0.14.0, sha256-pinned, ~180 MB, git-ignored
export JAVA_HOME=$(brew --prefix openjdk@21)
./gradlew :app:assembleFossDebug          # app/build/outputs/apk/foss/debug/thirdear-1-foss-debug.apk
./gradlew :app:testFossDebugUnitTest :app:lintFossDebug
```

Debug builds are `app.thirdear.debug` and coexist with a release (`app.thirdear`). The Kotlin
package is `app.thirdear` too; remote `upstream` (FossifyOrg/Voice-Recorder) is kept for reference.

## Docs

- [docs/THIRD-EAR-PLAN.md](docs/THIRD-EAR-PLAN.md): decisions, architecture, status.
- [docs/TESTING.md](docs/TESTING.md): what to try on the phone and in the car.

## License

GPLv3, as Fossify Voice Recorder. The spoken cues (Lisa's voice) come from I Hear You.

<!-- jooray-links:start -->
### More from me

**Related projects**

- [android-bluetooth-microphone](https://github.com/jooray/android-bluetooth-microphone): how to record from Bluetooth headsets and hearing aids on Android, and transcribe on the phone
- [livecaster-llm](https://github.com/jooray/livecaster-llm): a live co-pilot for podcast hosts, with local transcription and an LLM that keeps your outline honest
- [markdown2audio](https://github.com/jooray/markdown2audio): turn Markdown into an audio file with StyleTTS

**Full project showcase:** [Third Ear in my project showcase](https://juraj.bednar.io/showcase/#MED-05), or [all my projects](https://juraj.bednar.io/showcase/).

I write about building things on [my blog](https://juraj.bednar.io/en/blog-en/). I also wrote a cypherpunk novel, [Tamers of Entropy](https://tamersofentropy.net/), and there is a [trailer](https://tamersofentropy.net/#trailer).
<!-- jooray-links:end -->
