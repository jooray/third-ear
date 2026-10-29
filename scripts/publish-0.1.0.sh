#!/bin/sh
# Publishes Third Ear 0.1.0: public GitHub repo (README), the website on GitHub Pages,
# a tagged GitHub release with the signed APK, then Zapstore (signed with Amber).
# Run from the repo root in a real terminal: sh scripts/publish-0.1.0.sh
# Each step stops on error; rerunning skips what already exists where it can.
set -eu
cd "$(dirname "$0")/.."

APK=release/thirdear-0.1.0.apk
BT="$HOME/Library/Android/sdk/build-tools/36.0.0"
EXPECTED=79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d

echo "== 1. check the APK's signing certificate"
"$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | grep -q "SHA-256 digest: $EXPECTED" \
  || { echo "APK not signed with the publisher key"; exit 1; }

echo "== 2. public GitHub repo with main and the v0.1.0 tag"
if ! gh repo view jooray/third-ear >/dev/null 2>&1; then
  gh repo create jooray/third-ear --public \
    --description "Third Ear: the voice recorder for the space age. Bluetooth and hearing-aid microphones, on-device transcription, White Noise, Android Auto. Fork of Fossify Voice Recorder." \
    --homepage "https://jooray.github.io/third-ear/"
fi
git remote get-url origin >/dev/null 2>&1 || git remote add origin git@github.com:jooray/third-ear.git
git push -u origin main
git push origin v0.1.0

echo "== 3. website on GitHub Pages (the website/ folder as the gh-pages branch)"
git branch -f gh-pages "$(git subtree split --prefix website main)"
git push -f origin gh-pages
gh api -X POST repos/jooray/third-ear/pages -f "source[branch]=gh-pages" -f "source[path]=/" >/dev/null 2>&1 \
  || gh api -X PUT repos/jooray/third-ear/pages -f "source[branch]=gh-pages" -f "source[path]=/" >/dev/null
echo "   https://jooray.github.io/third-ear/ (first deploy takes a minute)"

echo "== 4. GitHub release v0.1.0 with the signed APK"
gh release view v0.1.0 -R jooray/third-ear >/dev/null 2>&1 \
  || gh release create v0.1.0 "$APK" -R jooray/third-ear --title "Third Ear 0.1.0" --notes-file docs/RELEASE_NOTES_0.1.0.md

echo "== 5. Zapstore: check, then publish (accept the Amber notifications on the phone)"
~/go/bin/zsp publish --check zapstore.yaml
SIGN_WITH="$(sed -n 's/^SIGN_WITH=//p' ~/projects/PulseLibre/.env)" ~/go/bin/zsp publish --skip-preview zapstore.yaml
echo "   https://zapstore.dev/apps/app.thirdear"
