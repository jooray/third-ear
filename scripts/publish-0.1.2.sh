#!/bin/sh
# Publishes Third Ear 0.1.2: the Bluetooth recording guide as its own public repo (the website
# links to it; the old gist points there), the v0.1.2 tag and GitHub release with the signed APK,
# the website, then Zapstore (signed with Amber).
# Run from the repo root in a real terminal: sh scripts/publish-0.1.2.sh
set -eu
cd "$(dirname "$0")/.."

APK=release/thirdear-0.1.2.apk
BT="$HOME/Library/Android/sdk/build-tools/36.0.0"
EXPECTED=79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d

echo "== 1. check the APK's signing certificate and version"
"$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | grep -q "SHA-256 digest: $EXPECTED" \
  || { echo "APK not signed with the publisher key"; exit 1; }
"$BT/aapt2" dump badging "$APK" | grep -q "versionCode='3' versionName='0.1.2'" \
  || { echo "APK is not versionCode 3 / 0.1.2"; exit 1; }

echo "== 2a. the Bluetooth recording guide: public repo, and the gist points to it"
GUIDE="$HOME/projects/android-bluetooth-microphone"
gh repo view jooray/android-bluetooth-microphone >/dev/null 2>&1 \
  || gh repo create jooray/android-bluetooth-microphone --public --source "$GUIDE" --push \
       --description "Recording from a Bluetooth microphone (headsets, LE Audio, hearing aids) on Android, and transcribing it on the phone. Also an agent skill."
GIST=08ad9660329e023435586e1d4b29272b
TMP="$(mktemp -d)"
{ printf '> **Moved:** this guide now lives at https://github.com/jooray/android-bluetooth-microphone (with updates: the 2.5-3 s wait, when to tell the user to speak, on-device transcription with Offline Speech to Text).\n\n'
  cat "$GUIDE/SKILL.md"; } > "$TMP/SKILL.md"
gh gist edit "$GIST" -f SKILL.md "$TMP/SKILL.md"
gh gist edit "$GIST" --desc "Moved to https://github.com/jooray/android-bluetooth-microphone (recording from a Bluetooth microphone on Android)"

echo "== 2. tag v0.1.2 and push"
git rev-parse -q --verify refs/tags/v0.1.2 >/dev/null || git tag -a v0.1.2 -m "Third Ear 0.1.2"
git push origin main
git push origin v0.1.2

echo "== 3. GitHub release v0.1.2 with the signed APK"
gh release view v0.1.2 -R jooray/third-ear >/dev/null 2>&1 \
  || gh release create v0.1.2 "$APK" -R jooray/third-ear --title "Third Ear 0.1.2" --notes-file docs/RELEASE_NOTES_0.1.2.md

echo "== 3b. website on GitHub Pages (the website/ folder as the gh-pages branch)"
git branch -f gh-pages "$(git subtree split --prefix website main)"
git push -f origin gh-pages

echo "== 4. Zapstore: check, then publish (accept the Amber notifications on the phone)"
~/go/bin/zsp publish --check zapstore.yaml
SIGN_WITH="$(sed -n 's/^SIGN_WITH=//p' ~/projects/PulseLibre/.env)" ~/go/bin/zsp publish --skip-preview zapstore.yaml
echo "   https://zapstore.dev/apps/app.thirdear"
