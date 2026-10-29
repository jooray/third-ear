# Testing Third Ear

Install the debug build next to everything else (it is `app.thirdear.debug`, its own app). The
signed release (`./gradlew :app:assembleFossRelease`, shared publisher key) is `app.thirdear`:

```sh
adb -s 5B171FDCG000M1 install -r --user 0 app/build/outputs/apk/foss/debug/thirdear-1-foss-debug.apk
```

First launch asks for the microphone, notifications and the recordings folder (Fossify's flow:
pick or create a folder, e.g. `Recordings/Third Ear`, then *Use this folder*).

## On the phone (10 minutes)

1. **Plain recording.** Recorder tab → tap **Save**. The buttons turn into "Stop → Save" /
   "Stop → Transcript to clipboard"; the source button says *Phone microphone*. Talk 10 s, tap
   **Stop → Save**. The *Last recording* card says *Saved as …*; the file plays in the Player tab.
2. **Transcript.** Tap **Transcript to clipboard**, talk, stop with it. The notification shows
   *Saving… → Transcribing…*; the card shows the text; paste it somewhere. With OSTT's keyboard
   dictating at the same time you should see *Waiting for the transcription app…* and then the
   transcript after the keyboard finishes.
3. **Destination at the stop.** Start with **Save**, stop with **Stop → Transcript to clipboard**:
   the recording gets a transcript although it started as a plain save.
4. **Bluetooth.** Settings → *Use a Bluetooth microphone*. With a headset or the aids connected the
   source button shows *Connecting… → Waiting for the Bluetooth microphone… → Bluetooth: name*, and
   (spoken cues on) "go ahead" plays in the headset once real sound arrives. Tap the source button
   mid-recording → *Switch for this recording* → it continues on the phone in the same file.
   Turn the headset off mid-recording: a falling three-note cue, the button says *Phone microphone*
   and the reason.
5. **Screen off.** Start recording, press power, wait 30 s, wake and unlock: the notification is
   still counting, and the file's length matches the wall-clock time with no silent gap. Same with
   a Bluetooth recording. Only Stop (screen, tile, notification, car) ends it.
6. **Silence watchdog.** Start recording, toggle the system microphone off in Quick Settings: after
   ~5 s *No audio detected* (notification + warning tone); toggle it back on, the warning clears.
7. **Crash safety.** Start recording, talk 20 s, `adb shell am force-stop app.thirdear.debug`,
   open the app again: a notification *Interrupted recording saved* and the file is in the list.
8. **Tiles.** Pull down Quick Settings → edit → add *Third Ear 1/2/3*. Tap one: recording starts
   with that action (a brief invisible activity does it). Tap again: stops with it.
9. **Share / webhook.** Settings → Actions → Add action → *Share* (or *Webhook* with a URL). Stop
   with it: the share sheet opens (or a *Ready to share* notification if the screen was off).

## Backup and restore

Backups hold the settings only. To check on a phone with backups enabled:

```sh
adb shell bmgr backupnow app.thirdear            # or app.thirdear.debug
adb shell bmgr restore <token> app.thirdear       # token from `adb shell bmgr list sets`
```

After a restore (new phone, or reinstall + restore) the actions, tiles and group settings are
back; the first recording asks for the folder again; White Noise is off and its screen says so:
turn it on (a new identity is created), then *Import nsec* with the backed-up key if you have one.
Groups have to invite Third Ear again either way.

## White Noise

1. Settings → White Noise → *Use White Noise*. After *Connected* the npub and its QR show.
2. Either *Create a test group* (only Third Ear in it), or invite the npub to a group in White
   Noise and *Accept* it here (the list refreshes every 15 s while the screen is open).
3. Settings → Actions → add one with destination *White Noise group*, pick the group, *audio +
   transcript*. Record, stop with it: the voice note arrives in White Noise with the transcript as
   caption.
4. Tap the group → messages *Play voice messages and read text*, *When I ask*. Send a message to the
   group from White Noise: a notification with *Reply*, *Mark as read*, *Read aloud*. *Read aloud*
   reads it; *Reply* sends your text to the group. *Recent messages* lists it with Play / Read.
5. A message containing 🎙️ is ignored (shown in the list as ignored, no notification).
6. Key backup: *Show nsec* (copy to the password manager); *Import nsec* replaces the identity
   (groups must invite again).

Only use Third Ear's own identity and a group you created for it; never test against groups that
are in daily use.

## In the car (Android Auto)

Enable unknown sources first (see README). Then, connected to the car:

1. The launcher shows Third Ear; it opens a grid of your actions.
2. Tap an action: the recording screen shows the microphone (with *Use a Bluetooth microphone* on:
   the car's hands-free microphone, "Waiting for the Bluetooth microphone…" first) and the clock.
   Tap a "Stop → …" row: it stops, saves and does that action.
3. Send a message to the White Noise group from another phone: Android Auto announces it ("New
   message from …, read it?") and offers to reply by voice. Third Ear never reads it by itself in
   the car, even for groups set to *automatically*.
4. *Messages* in the action strip lists recent messages; tapping one plays it.

Without a car: the Desktop Head Unit (`sdkmanager "extras;google;auto"`, then on the phone
Android Auto → Developer settings → *Start head unit server*, `adb forward tcp:5277 tcp:5277`,
`~/Library/Android/sdk/extras/google/auto/desktop-head-unit`). The car screens' logic is covered by
`CarModelTest`; the reply rules (car → ask) by `ReplyPolicyTest`.

## Automated tests

`./gradlew :app:testFossDebugUnitTest` — engine (fake microphones: Bluetooth that never sounds,
late LE Audio start, disconnects, user switch, pause, read errors, silence), source policy, frame
journal and finaliser (truncated journals), actions and destination routing, action store and
settings (Robolectric), retry/backoff, resampler, notes, reply policy, car model.
