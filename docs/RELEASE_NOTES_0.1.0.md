# Third Ear 0.1.0

The first release of Third Ear, a voice recorder for Android 12+ built on Fossify Voice Recorder.

- **Action buttons.** Each button records and then saves, copies the transcript, shares, posts to a webhook or sends to a White Noise group. While recording, every button reads "Stop → action", so you can still change your mind when you stop.
- **Bluetooth microphones that work.** Headsets, LE Audio hearing aids and the car's hands-free microphone. The app waits for LE Audio to start, shows which microphone really records and lets you switch mid-recording into the same file. It falls back to the phone once, with a cue. With the car and headphones both connected, it uses the headphones' microphone.
- **On-device transcription** with [Offline Speech to Text](https://zapstore.dev/apps/app.offlinespeechtotext). It works with the screen locked and waits while the app is busy. Transcripts are saved next to the recording.
- **White Noise.** Third Ear has its own identity. It can send voice notes or transcripts to your groups, including groups with AI agents. Replies are played or read aloud, automatically or when you ask.
- **Android Auto.** Your actions on the car screen, and messages announced with Reply and Mark as read. See the website for how to enable it.
- **Built for long recordings.** It keeps recording with the screen off and the phone locked. A crash loses at most the last second, and the silence watchdog warns when the microphone delivers nothing.
- Quick Settings tiles and a widget. Languages: English, Slovak, Czech, German, Spanish.

Signing certificate SHA-256: `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`
