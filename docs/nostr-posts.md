# Third Ear: Nostr posts

Plain text, one post per block. Each ends with the website and the Zapstore link, then its images
(screenshots from the emulator with demo data, on Blossom, uploaded with the Third Ear key).
Post 3 describes 0.1.3: post it once 0.1.3 is released.

## 1. Launch

```text
Third Ear: the voice recorder for the space age.

It records from your phone, a Bluetooth headset, LE Audio hearing aids or your car. The transcript is made on the phone, and the result (transcript or audio) goes wherever the button you tapped says: phone storage, the clipboard, a White Noise group or an HTTPS request.

Most recorders have one record button and a lot of hope. Third Ear has the buttons you define, like a small mission control.

Free, open source (GPLv3), no account, no analytics. Android 12+.

Web: https://jooray.github.io/third-ear/
Zapstore: https://zapstore.dev/apps/app.thirdear

#android #foss #nostr #whitenoise

https://blossom.primal.net/4691b31bf42e01da0e46bd44dedb5942fc2262cb196230e3c81e74c7da52d6de.png

https://blossom.primal.net/36f3546ae0f732383e294ec8350f6f8883c6f2c427b97abdff6d5fe0392ac3a1.png
```

## 2. Talk to your agents

```text
Typing to an AI agent on a phone is slow, and the voice modes of most apps want you to stare at the screen.

With Third Ear you tap a button (or a Quick Settings tile, the widget, the car screen), speak, and the phone transcribes. The words go to your agent: into an encrypted White Noise group (hermes-agent joins through the MDK connector) or to a webhook. The answer comes back and is read aloud into your headset or hearing aids.

Walking, driving, cooking. No screen needed.

Web: https://jooray.github.io/third-ear/
Zapstore: https://zapstore.dev/apps/app.thirdear

#ai #agents #whitenoise #marmot

https://blossom.primal.net/76cbd2b6af38616aad0366b512f680115dd8810709a9bd1016901eefe865cccb.png

https://blossom.primal.net/3791fbd9ba7c366d408aa2d531d971f99f454cc970399c63fde12a0c373a555c.png
```

## 3. Messages, a player and tablets (0.1.3)

```text
Third Ear 0.1.3 is out.

With White Noise on, a Messages tab lists your groups like a chat app. Open one and you can type, or record straight into it: the big key sends your voice, or only the transcript. It works for any group, with or without a button on the Board.

Voice messages get a real player: length, a waveform you can tap or drag to jump, pause, and speed from 1x to 2x. Set a default speed, and a different one per group: an agent that already talks fast stays at 1x, people play at 1.5x. Mark all as read, per group or everywhere. On a tablet the groups and the open conversation sit side by side.

Also: the recordings folder is simply the folder you pick.

Web: https://jooray.github.io/third-ear/
Zapstore: https://zapstore.dev/apps/app.thirdear

#whitenoise #marmot #nostr #android

https://blossom.primal.net/87bcdc65f962e545943fe80941a585feaedbcfbc92092d58cfc43178ba6cf32e.png

https://blossom.primal.net/8498447047c52fa8503d294288a1eea3f62d694db8b90601d6c1d6bde2d37faa.png

https://blossom.primal.net/e88fb895692eef4ce42f2e464db954e17af60936fd7b70bd6a28d89644c77152.png
```

## 4. Bluetooth microphones and hearing aids

```text
Recording from a Bluetooth microphone on Android is surprisingly hard. Most apps quietly record from the phone instead, and nobody notices until the file sounds wrong.

Third Ear gets it right: headsets, LE Audio hearing aids and the car's hands-free microphone. It waits for the Bluetooth mic to come up (usually 2.5 to 3 seconds), tells you when to start talking, shows which microphone is actually recording, and falls back to the phone with an audible cue if the headset gives up. You can switch microphones mid-recording and it stays one file.

Web: https://jooray.github.io/third-ear/
Zapstore: https://zapstore.dev/apps/app.thirdear

#android #bluetooth #hearingaids #accessibility

https://blossom.primal.net/aafb09103cdd19b064e92ca740fa414f2d6509f5b2779e0155a53f4c1217d6e3.png

https://blossom.primal.net/36f3546ae0f732383e294ec8350f6f8883c6f2c427b97abdff6d5fe0392ac3a1.png
```

## 5. Transcripts that stay on the phone

```text
Third Ear has no speech engine of its own and no server. It hands the finished recording to Offline Speech to Text, a free app that transcribes on the device, offline, even with the screen locked.

Make a "Meeting notes" button: record, get the transcript. Or "Transcript to clipboard": record, stop, paste into any app. Transcripts are plain .txt files next to the audio, so you can search them.

Your voice doesn't leave the phone unless you tell it to, and when it does, it can go end-to-end encrypted.

Web: https://jooray.github.io/third-ear/
Zapstore: https://zapstore.dev/apps/app.thirdear
Offline Speech to Text: https://zapstore.dev/apps/app.offlinespeechtotext

#privacy #foss #android

https://blossom.primal.net/3791fbd9ba7c366d408aa2d531d971f99f454cc970399c63fde12a0c373a555c.png

https://blossom.primal.net/fab4e5baee1bae081785736834f4b9c230e6333d67270f9a3ac1c6f8651e3fd9.png
```

## 6. In the car

```text
Third Ear works with Android Auto.

Your buttons show up as a grid on the car screen, it records with the car's microphone, and you stop on whichever destination you want. Incoming White Noise messages arrive as car notifications with Reply and Mark as read, and Android Auto reads them when you ask.

It isn't from the Play Store, so Android Auto needs "Unknown sources" turned on once. The website has the steps.

Web: https://jooray.github.io/third-ear/
Zapstore: https://zapstore.dev/apps/app.thirdear

#androidauto #android #foss

https://blossom.primal.net/fc800ce94f1485a5224fd857a1eb365b3aaf804435c34011c507f1eb54393c07.png

https://blossom.primal.net/ae140476a7d959f42758cc427a5f0e1c5ff24ed3474a58d52fda1a7c859aaeee.png
```

## 7. Built for long recordings

```text
Third Ear keeps recording with the screen off and the phone locked, as long as you need.

If the app gets killed, what was recorded is recovered on the next launch; at most the last second is lost. A silence watchdog warns you when the microphone delivers digital silence (mic switch off, another app holding it). Every line on the Board has a lamp: amber while transcribing or sending, white when a reply waits, red when something wasn't delivered, with Retry one tap away.

AAC, Opus or MP3. Tiles, a widget, five languages.

Web: https://jooray.github.io/third-ear/
Zapstore: https://zapstore.dev/apps/app.thirdear

#android #foss

https://blossom.primal.net/af2221688826b64d15226ec8d75f01a18c53fe981baba67e9b9ae20b5674dc6e.png

https://blossom.primal.net/3387d668b5fa8b0db96d3e0b10aadd08dab97c83a91c1634c9e87048288c6b69.png
```
