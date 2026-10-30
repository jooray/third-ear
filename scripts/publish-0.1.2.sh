#!/bin/sh
# Publishes Third Ear 0.1.2: the v0.1.2 tag and GitHub release with the signed APK, then
# Zapstore (signed with Amber). The repo and the website already exist (see publish-0.1.0.sh).
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

echo "== 2. tag v0.1.2 and push"
git rev-parse -q --verify refs/tags/v0.1.2 >/dev/null || git tag -a v0.1.2 -m "Third Ear 0.1.2"
git push origin main
git push origin v0.1.2

echo "== 3. GitHub release v0.1.2 with the signed APK"
gh release view v0.1.2 -R jooray/third-ear >/dev/null 2>&1 \
  || gh release create v0.1.2 "$APK" -R jooray/third-ear --title "Third Ear 0.1.2" --notes-file docs/RELEASE_NOTES_0.1.2.md

echo "== 4. Zapstore: check, then publish (accept the Amber notifications on the phone)"
~/go/bin/zsp publish --check zapstore.yaml
SIGN_WITH="$(sed -n 's/^SIGN_WITH=//p' ~/projects/PulseLibre/.env)" ~/go/bin/zsp publish --skip-preview zapstore.yaml
echo "   https://zapstore.dev/apps/app.thirdear"
