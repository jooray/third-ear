---
name: Third Ear
description: A voice recorder laid out as a switchboard; every action is a line with a cord colour, a code, a lamp and a printed strip.
colors:
  bakelite-face: "#16130F"
  bakelite-plate: "#211D18"
  bakelite-plate-hi: "#302A23"
  bakelite-recess: "#0E0C09"
  bakelite-rule: "#3A342B"
  bakelite-rule-strong: "#5A5246"
  bakelite-ink: "#EDE8DA"
  bakelite-ink-muted: "#ADA595"
  bakelite-strip: "#E6E0CE"
  bakelite-strip-ink: "#1E1A14"
  bakelite-strip-rule: "#B4AD9B"
  bakelite-lamp-off: "#2E2922"
  bakelite-lamp-bezel: "#4A4337"
  bakelite-reply: "#F7F4EA"
  bakelite-fault: "#E5483A"
  bakelite-fault-ink: "#FF8A7A"
  bakelite-busy-ink: "#F2B65E"
  paper-face: "#E3DDCD"
  paper-plate: "#F3EFE4"
  paper-plate-hi: "#D8D1BE"
  paper-recess: "#D9D2C0"
  paper-rule: "#C3BBA7"
  paper-rule-strong: "#8C8472"
  paper-ink: "#17140F"
  paper-ink-muted: "#575045"
  paper-strip: "#1E1A15"
  paper-strip-ink: "#EDE8DA"
  paper-strip-rule: "#4A4337"
  paper-lamp-off: "#DDD6C5"
  paper-lamp-off-bezel: "#A39B88"
  paper-reply: "#FFF1C2"
  paper-fault: "#D63A2B"
  paper-fault-ink: "#A8281B"
  paper-busy-ink: "#7E5200"
  lamp-busy: "#F2A33A"
  cord-red: "#C8321E"
  cord-green: "#2F7D4F"
  cord-blue: "#2C5AA0"
  cord-yellow: "#D9B23A"
  cord-white: "#E6E0CE"
  cord-violet: "#7A4FA0"
  cord-orange: "#D9762B"
  cord-teal: "#23807F"
  cord-ink-dark: "#17140F"
  cord-ink-light: "#F7F4EA"
typography:
  timer:
    fontFamily: "Saira Condensed, sans-serif"
    fontSize: "88sp"
    fontWeight: 700
    lineHeight: "88sp"
    fontFeature: "tnum"
  wordmark:
    fontFamily: "Saira Condensed, sans-serif"
    fontSize: "22sp"
    fontWeight: 700
    letterSpacing: "0.05em"
  screen-title:
    fontFamily: "Atkinson Hyperlegible Next, sans-serif"
    fontSize: "24sp"
    fontWeight: 700
    lineHeight: "30sp"
  key-name:
    fontFamily: "Atkinson Hyperlegible Next, sans-serif"
    fontSize: "19sp"
    fontWeight: 700
    lineHeight: "23sp"
  title:
    fontFamily: "Atkinson Hyperlegible Next, sans-serif"
    fontSize: "17sp"
    fontWeight: 700
    lineHeight: "22sp"
  body:
    fontFamily: "Atkinson Hyperlegible Next, sans-serif"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "23sp"
  small:
    fontFamily: "Atkinson Hyperlegible Next, sans-serif"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "19sp"
  button:
    fontFamily: "Atkinson Hyperlegible Next, sans-serif"
    fontSize: "15sp"
    fontWeight: 700
    lineHeight: "20sp"
  figures:
    fontFamily: "Atkinson Hyperlegible Next, sans-serif"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "19sp"
    fontFeature: "tnum"
  label:
    fontFamily: "Saira Condensed, sans-serif"
    fontSize: "13sp"
    fontWeight: 600
    lineHeight: "16sp"
    letterSpacing: "0.12em"
  code:
    fontFamily: "Saira Condensed, sans-serif"
    fontSize: "14sp"
    fontWeight: 700
    lineHeight: "16sp"
    letterSpacing: "0.1em"
  strip:
    fontFamily: "Saira Condensed, sans-serif"
    fontSize: "12sp"
    fontWeight: 600
    lineHeight: "15sp"
    letterSpacing: "0.06em"
rounded:
  cell: "3dp"
  plate: "6dp"
  lamp: "50%"
spacing:
  hair: "2dp"
  xs: "4dp"
  sm: "8dp"
  key-gap: "10dp"
  key-inset: "12dp"
  gutter: "16dp"
  section: "20dp"
  end: "24dp"
components:
  line-key:
    backgroundColor: "{colors.bakelite-plate}"
    textColor: "{colors.bakelite-ink}"
    typography: "{typography.key-name}"
    rounded: "{rounded.plate}"
    padding: "10dp 4dp 0 12dp"
    height: "136dp"
  line-key-pressed:
    backgroundColor: "{colors.bakelite-plate-hi}"
  line-key-reversed:
    backgroundColor: "{colors.bakelite-strip}"
    textColor: "{colors.bakelite-strip-ink}"
  code-cell:
    backgroundColor: "{colors.cord-red}"
    textColor: "{colors.cord-ink-light}"
    typography: "{typography.code}"
    rounded: "{rounded.cell}"
    padding: "3dp 7dp"
  designation-strip:
    backgroundColor: "{colors.bakelite-strip}"
    textColor: "{colors.bakelite-strip-ink}"
    typography: "{typography.strip}"
    padding: "5dp 8dp"
  lamp:
    backgroundColor: "{colors.bakelite-lamp-off}"
    rounded: "{rounded.lamp}"
    size: "16dp"
  lamp-busy:
    backgroundColor: "{colors.lamp-busy}"
  lamp-reply:
    backgroundColor: "{colors.bakelite-reply}"
  lamp-fault:
    backgroundColor: "{colors.bakelite-fault}"
  plate:
    backgroundColor: "{colors.bakelite-plate}"
    rounded: "{rounded.plate}"
  plate-recess:
    backgroundColor: "{colors.bakelite-recess}"
    rounded: "{rounded.plate}"
  button-key:
    backgroundColor: "{colors.bakelite-ink}"
    textColor: "{colors.bakelite-face}"
    typography: "{typography.button}"
    rounded: "{rounded.plate}"
    padding: "0 16dp"
    height: "48dp"
  button-outline:
    textColor: "{colors.bakelite-ink}"
    typography: "{typography.button}"
    rounded: "{rounded.plate}"
    padding: "0 14dp"
    height: "48dp"
  button-plain:
    textColor: "{colors.bakelite-ink}"
    typography: "{typography.button}"
    rounded: "{rounded.plate}"
    padding: "0 12dp"
    height: "48dp"
  notice:
    backgroundColor: "{colors.bakelite-recess}"
    textColor: "{colors.bakelite-ink}"
    typography: "{typography.small}"
    rounded: "{rounded.plate}"
    padding: "10dp 4dp 10dp 12dp"
  setting-row:
    textColor: "{colors.bakelite-ink}"
    typography: "{typography.body}"
    padding: "10dp 16dp"
    height: "56dp"
  nav-bar:
    backgroundColor: "{colors.bakelite-plate}"
    textColor: "{colors.bakelite-ink-muted}"
    typography: "{typography.small}"
  nav-bar-selected:
    backgroundColor: "{colors.bakelite-plate-hi}"
    textColor: "{colors.bakelite-ink}"
---

# Design System: Third Ear

## Overview

**Creative North Star: "The Jack Field"**

Third Ear is a switchboard face. Every action is a line: a key with its own cord colour, a three-letter line code printed in a coloured cell, an opal lamp that reports its state, and a printed designation strip that says what happens after the stop. A power user reads the whole board at a glance: which microphone is live, which lines are busy, which have replies waiting, which failed. The category default (a round record button over a waveform, with a recordings list) is refused; recording starts by tapping a line and ends by tapping the line it should go down.

The face exists in two materials. Dark is bakelite black; light is the warm designation-strip paper itself. Both are flat: depth is a tonal step and a 1 dp rule, never a shadow. Density is whole-cell: keys are full plates with generous targets (136 dp minimum height, 48 dp minimum for every button, 56 dp setting rows) because the audience includes headset and hearing-aid wearers and drivers glancing at a mounted phone.

Colour is functional throughout. Cord colours identify lines and nothing else; lamp colours report state and never appear without a word; everything else is ink on face. Motion is a relay, not a fade: two hard frames, no easing.

**Key Characteristics:**
- Line keys in cord colours with code cell, lamp, name, state word and designation strip.
- Two flat faces (bakelite and paper), tonal steps and 1 dp rules, no shadows.
- Opal lamps with four states, always paired with a word.
- Atkinson Hyperlegible Next for everything read; Saira Condensed caps for strips, codes and labels.
- One reversed plate per screen and one giant live figure while recording.
- Relay-step motion: hard frames, no easing; still when animations are off.

## Colors

A warm near-black and warm paper pair carrying eight saturated cord colours and three opal lamp colours; neutrals carry everything that is not identity or state.

### Primary
- **Cord Red, Green, Blue, Yellow, White, Violet, Orange, Teal** (`cord-*`): line identity. A cord colour is the fill of a line's code cell and the colour of that line on its key, Quick Settings tile, widget, notification and Android Auto grid. Each line gets one; the default is its position, the editor lets the user pick from the eight swatches. Code text is chosen by luminance: Cord Ink Dark (`cord-ink-dark`) on Yellow and White, Cord Ink Light (`cord-ink-light`) on the rest. On the paper face, the White cord's cell gets a 1 dp Strong Rule edge so it does not vanish.

### Secondary
- **Busy Amber** (`lamp-busy`): the busy lamp (processing: saving, transcribing, waiting for OSTT, sending), the busy cell in the last-recording pipeline strip, the border of a non-fault Notice, and the last two segments of the live level meter near clipping. Same on both faces. Text in amber uses Busy Ink (`bakelite-busy-ink` / `paper-busy-ink`) so it stays readable.
- **Opal White / Reply Cream** (`bakelite-reply`, `paper-reply`): the reply lamp, lit while replies from that line's White Noise group are unread. On paper the lamp is a warm cream with an amber halo; on bakelite a white lamp with a faint white halo. The Log badge in the navigation is the same lamp (on paper it prints in ink).
- **Fault Red** (`bakelite-fault`, `paper-fault`): the "not delivered" lamp, the failed pipeline cell, the fault Notice border. Text in fault colour uses Fault Ink (`bakelite-fault-ink`, `paper-fault-ink`): the Not delivered word, Discard, Delete.

### Neutral
- **Bakelite Face / Paper Face** (`*-face`): the screen ground.
- **Plate** (`*-plate`): keys, the input bar, plates, navigation bar and rail; one tonal step off the face (lighter on bakelite, lighter on paper).
- **Plate High** (`*-plate-hi`): pressed keys and the selected navigation indicator.
- **Recess** (`*-recess`): sunk surfaces: the recording plate and Notice bands.
- **Rule / Strong Rule** (`*-rule`, `*-rule-strong`): 1 dp plate edges and row hairlines; Strong Rule for outline buttons and the white-cord edge.
- **Ink / Muted Ink** (`*-ink`, `*-ink-muted`): text and icons. Muted Ink for secondary lines, labels and unselected navigation.
- **Strip / Strip Ink / Strip Rule** (`*-strip*`): the designation strip. Paper on the bakelite face, black tape on the paper face; the two faces swap it, so the strip always contrasts with its key.
- **Lamp Off and bezels** (`*-lamp-off`, `bakelite-lamp-bezel`, `paper-lamp-off-bezel`): an unlit lamp. On paper the unlit bezel is lighter than the lit bezel (ink), so an unlit lamp never reads as lit.

### Named Rules
**The Cord Is Identity Rule.** Cord colours mark a line and nothing else: no cord colour as decoration, accent, chart colour or button fill. If it is not a line, it is ink on face.

**The Lamp Speaks Rule.** A lit lamp always travels with a word ("Transcribing", "1 reply waiting", "Not delivered"); colour alone never carries state. The same applies to Notices.

**The No Dynamic Color Rule.** Material Dynamic Color is off. Wallpaper-derived colour would overwrite cord identity; the Material scheme is mapped onto the face palette instead.

## Typography

**Display Font:** Saira Condensed (Medium/SemiBold/Bold), used in caps
**Body Font:** Atkinson Hyperlegible Next (static 400/600/700 instances cut with fonttools from the variable font)
**Label/Mono Font:** Saira Condensed for labels, codes and strips; system monospace only for raw technical values

**Character:** Atkinson carries everything that is read, chosen for legibility at a glance; Saira Condensed is the switchboard's printed engraving, used only in caps for short designations. Figures that change on screen are tabular.

### Hierarchy
- **Timer** (Saira Condensed 700, 88 sp, tabular): the one giant live figure on the recording plate; drops to 60 sp past an hour so the plate keeps its width.
- **Wordmark** (Saira Condensed 700, 22 sp, 0.05 em): "THIRD EAR" at the top of the Board; "3E" heads the rail.
- **Screen title** (Atkinson 700, 24/30 sp): screen headings in Log, Lines, Setup.
- **Key name** (Atkinson 700, 19/23 sp): a line's name on its key and widget, up to two lines.
- **Title** (Atkinson 700, 17/22 sp): list item and sheet titles.
- **Body** (Atkinson 400, 16/23 sp): reading text, setting titles, transcripts, replies.
- **Small** (Atkinson 400, 14/19 sp; 700 for state words): secondary lines, state words under lamps, navigation labels.
- **Button** (Atkinson 700, 15/20 sp): all buttons.
- **Figures** (Atkinson 400, 14/19 sp, tabular): durations, timestamps, counts.
- **Label** (Saira Condensed 600, 13 sp, 0.12 em, caps): engraved section labels ("LINES · TAP TO RECORD", "REPLIES WAITING"), marked as headings for TalkBack.
- **Code** (Saira Condensed 700, 14 sp, 0.1 em, caps): line codes and the reply badge count.
- **Strip** (Saira Condensed 600, 12 sp, 0.06 em, caps): designation strip and pipeline cells.

### Named Rules
**The Condensed Is Engraving Rule.** Saira Condensed appears only in caps, only for short designations (codes, strips, labels, wordmark, timer). Sentences, names and anything read for meaning are Atkinson.

**The Tabular Figures Rule.** Numbers that change while visible (timer, durations, counts) use tabular figures so they never jitter.

## Layout

A single scrolling column with a 16 dp gutter. The Board pins its top (56 dp wordmark row and the input bar) so the microphone stays in view while the lines scroll beneath. Below it: the recording plate when live, the section label row (20 dp above, 10 dp below), then the jack field: line keys in equal-height rows with 10 dp gaps both ways, padded with empty cells so a short last row keeps column width. After the field come the replies plate and the last-recording plate, with 24 dp at the end.

Columns: two on phones; one when font scale is 1.3 or more; on expanded width (600 dp and up) the Board takes the full width with `(width − rail) / 200` columns, clamped to 2–4. Log, Lines and Setup keep a reading measure capped at 600 dp on wide screens.

Navigation: bottom bar (Board, Log, Lines, Setup) on phones, above a 1 dp rule; from 600 dp a navigation rail with the "3E" wordmark, separated from content by a 1 dp vertical rule. The Board carries no search and no menu: search lives in Log, settings live in Setup.

Spacing rhythm: 2, 4, 8, 10, 12, 16, 20, 24 dp. Keys inset their content 12 dp; settings rows and screen gutters 16 dp.

## Elevation & Depth

Flat. There are no shadows anywhere: depth is a tonal step (face, plate, plate high, recess) plus a 1 dp rule. Material components are forced flat (`tonalElevation = 0`, the FAB with zero elevation in every state). Sunk surfaces (recording plate, Notices) use the Recess tone; raised surfaces use Plate.

### Named Rules
**The Tonal Step Rule.** To separate a surface, step its tone and draw a 1 dp rule. Never a shadow, never a glow.

**The One Reversed Plate Rule.** At most one plate per screen is reversed into strip colours: the line a recording started on ("Started here"). Reversal marks the one thing that is happening, so it is never used for emphasis elsewhere.

## Shapes

Two radii only: 3 dp for cells (code cells, level-meter segments, the pipeline strip) and 6 dp for plates (keys, input bar, plates, Notices, every button). Lamps and cord swatches are circles. Designation strips are square-cornered bands clipped by their key. The launcher icon is one line key drawn on the bakelite face (`graphics/icon.svg`, `res/drawable/ic_launcher_foreground.xml`).

## Components

### Line Key (signature)
The whole key is one target (min 136 dp tall, equal heights within a row). Top row: code cell, then the lamp at the right. Then the name (key name style, two lines max), then the state word under it in Small Strong: Busy Ink while busy, Fault Ink when not delivered, Ink for replies. The designation strip runs along the bottom edge.
- **Pressed:** an instant step to Plate High with a haptic tick; no ripple.
- **Long press:** opens the line in the editor (not while recording).
- **Recording:** every key shows "STOP →" in Label style after its code cell and its lamp goes off; tapping stops onto that line, and the strip flashes the cord colour for one 90 ms relay frame (skipped with animations off). The started line becomes the reversed plate with "Started here".
- **Accessibility:** merged semantics; TalkBack reads name, state word and what the line does.

### Code Cell
Three letters in Code style on the cord colour, 3 dp corners, 7 × 3 dp padding. Used on keys, the recording plate, reply rows and the widget.

### Designation Strip
What happens after the stop, as printed cells (OSTT, destination or group name, content) in Strip style, divided by 1 dp Strip Rule hairlines. The last cell ellipsises. The pipeline strip on the last-recording plate reuses it: done cells carry a check, the current cell fills amber (busy) or red (fault), unreached cells print at 40% ink.

### Lamp
A 16 dp opal circle in a 2 dp bezel inside a 24 dp box (12 dp in Notices and the recording plate). States: off, busy (amber, flashing 700 ms on / 300 ms off in hard steps), reply (white/cream with a halo), fault (red). With animations off, a busy lamp stays lit.

### Buttons
- **Shape:** plate corners (6 dp), minimum 48 dp tall, optional 20 dp leading icon with 8 dp gap.
- **Key (primary):** Ink fill, Face text; the one strong action on a plate.
- **Outline:** 1 dp Strong Rule border, Ink text (e.g. Pause/Resume).
- **Plain:** text only; Fault Ink for destructive actions (Discard, Delete).

### Cards / Containers (Plates)
- **Corner Style:** 6 dp.
- **Background:** Plate; Recess for the recording plate and Notices.
- **Shadow Strategy:** none (see Elevation & Depth).
- **Border:** 1 dp Rule.
- **Internal Padding:** 12–16 dp.

### Input Bar
A pinned plate (min 64 dp) naming the real input (phone or the Bluetooth device by name) with its route line beneath. Tapping switches source. While recording it shows a twelve-segment log-scale level meter (last two segments amber); idle it shows "Switch". There is no idle level meter: showing one would keep the microphone open and the system privacy indicator lit.

### Recording Plate
Recess plate: amber working lamp (red stays reserved for "not delivered"), "RECORDING" label and the line's code cell, then the giant timer; Pause (outline) and Discard (plain, fault ink) at the right. Kept compact so the stop keys stay within reach below it.

### Notice
A Recess band with a 1 dp amber (or red for faults) border, a 12 dp lamp, a sentence of state, and an optional plain action. Never colour alone.

### Inputs / Fields
Material fields and switches themed onto the face palette (primary = Ink, outline = Strong Rule, error = Fault Ink). Setting rows are whole-row targets, min 56 dp, title in Body with the value in Small Muted Ink beneath.

### Navigation
Plate-coloured bar or rail; selected item is Ink on a Plate High indicator, unselected Muted Ink; labels in Small. Unread replies show on Log as the reply lamp badge with a Code-style count.

### Widget
The home-screen widget is a line key in RemoteViews: code cell, state word in Label style, lamp, key name, designation strip, on the bakelite plate.

## Do's and Don'ts

### Do:
- **Do** give every action a cord colour, a line code, a lamp and a designation strip wherever it appears (key, tile, widget, notification, car grid).
- **Do** pair every lit lamp and every Notice with a word.
- **Do** separate surfaces with a tonal step and a 1 dp rule; corners are 3 dp (cells) or 6 dp (plates).
- **Do** animate in relay steps: hard frames, no easing (busy lamp 700/300 ms, strip patch flash 90 ms), and hold lamps lit when animations are off.
- **Do** keep targets at least 48 dp and setting rows at least 56 dp; drop the Board to one column at font scale 1.3 and above.
- **Do** cap lists and settings at a 600 dp reading measure on wide screens while the Board takes the full width.
- **Do** use tabular figures for anything that counts while visible.

### Don't:
- **Don't** use a cord colour for anything but a line's identity.
- **Don't** turn on Dynamic Color.
- **Don't** add shadows, glows or Material tonal elevation.
- **Don't** show an idle level meter on the input bar; the microphone stays closed until recording.
- **Don't** put search or a menu on the Board; search lives in Log, settings in Setup.
- **Don't** reverse more than one plate per screen.
- **Don't** set sentences or names in Saira Condensed, or Saira in lower case.

Raster provenance (website hero art, screenshots) is kept in `.impeccable/provenance.md`.
