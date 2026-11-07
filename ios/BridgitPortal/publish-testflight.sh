#!/usr/bin/env bash
# Bridgit Portal → TestFlight. Archives the app, signs it with the Sponic Gardens team's
# distribution certificate, uploads it and gives it to the "Bridgit Portal testers" group.
#
#   ios/BridgitPortal/publish-testflight.sh [--build-number N] [--skip-upload]
#
# The version (MARKETING_VERSION in project.yml) stays the same between builds on purpose: Apple's beta
# review is needed for the first build of every new version, and we don't want that wait on each update.
# Each build is told apart by its build number, the UTC time it was made (yymmddHHMM), which the app
# shows on the More screen. Raise the version by hand only when a review wait is acceptable.
#
# Secrets come from Bitwarden item "Apple Developer — sponic" (a0a81618-…) through
# /Users/otter/bin/bwaccesser and live only in a private temp folder that is deleted on exit.
# The signing identity goes into a temporary keychain, never the login keychain.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
BUNDLE_ID="cc.bridgit.portal"
APP_NAME="Bridgit Portal"
PROFILE_NAME="Bridgit Portal App Store"
GROUP="Bridgit Portal testers"
BW_ITEM="a0a81618-0949-4eb4-8286-b481015b9ff4"
BW="/Users/otter/bin/bwaccesser"

BUILD_NUMBER="$(date -u +%y%m%d%H%M)"
SKIP_UPLOAD=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-number) BUILD_NUMBER="$2"; shift 2 ;;
    --skip-upload) SKIP_UPLOAD=1; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

WORK="$(mktemp -d "${TMPDIR:-/tmp}/bridgit-portal-tf.XXXXXX")"
chmod 700 "$WORK"
KEYCHAIN="$WORK/signing.keychain-db"
cleanup() {
  security delete-keychain "$KEYCHAIN" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "==> Reading signing material from Bitwarden"
"$BW" status >/dev/null
item="$("$BW" bw get item "$BW_ITEM")"
field() { printf '%s' "$item" | python3 -c 'import sys,json; d=json.load(sys.stdin); print({f["name"]:f["value"] for f in d.get("fields") or []}[sys.argv[1]], end="")' "$1"; }
TEAM_ID="$(field TEAM_ID)"
KEY_ID="$(field KEY_ID)"
ISSUER_ID="$(field ISSUER_ID)"
CERT_ID="$(field DIST_CERT_ID)"
( umask 077
  field P8_PRIVATE_KEY > "$WORK/AuthKey.p8"
  field DIST_P12_PASSWORD > "$WORK/p12.pw"
)
unset item
"$BW" bw get attachment dist.p12 --itemid "$BW_ITEM" --output "$WORK/dist.p12" >/dev/null
export ASC_KEY_ID="$KEY_ID" ASC_ISSUER_ID="$ISSUER_ID" ASC_PRIVATE_KEY_PATH="$WORK/AuthKey.p8"

echo "==> Bundle ID $BUNDLE_ID"
bundle_res="$(asc bundle-ids list --paginate --output json | python3 -c 'import sys,json
for b in json.load(sys.stdin)["data"]:
  if b["attributes"]["identifier"]==sys.argv[1]: print(b["id"]); break' "$BUNDLE_ID")"
if [[ -z "$bundle_res" ]]; then
  bundle_res="$(asc bundle-ids create --identifier "$BUNDLE_ID" --name "$APP_NAME" --platform IOS --output json | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')"
fi

echo "==> App Store profile \"$PROFILE_NAME\""
profile_id="$(asc profiles list --paginate --output json | python3 -c 'import sys,json
for p in json.load(sys.stdin)["data"]:
  a=p["attributes"]
  if a["name"]==sys.argv[1] and a.get("profileState")=="ACTIVE": print(p["id"]); break' "$PROFILE_NAME")"
if [[ -z "$profile_id" ]]; then
  profile_id="$(asc profiles create --name "$PROFILE_NAME" --profile-type IOS_APP_STORE --bundle "$bundle_res" --certificate "$CERT_ID" --output json | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')"
fi
asc profiles download --id "$profile_id" --output "$WORK/app.mobileprovision" >/dev/null
asc profiles local install --path "$WORK/app.mobileprovision" --force >/dev/null  # later builds reuse the same profile

echo "==> Temporary keychain with the distribution identity"
KC_PW="$(openssl rand -hex 16)"
security create-keychain -p "$KC_PW" "$KEYCHAIN"
security set-keychain-settings -lut 3600 "$KEYCHAIN"
security unlock-keychain -p "$KC_PW" "$KEYCHAIN"
security import "$WORK/dist.p12" -k "$KEYCHAIN" -P "$(cat "$WORK/p12.pw")" -T /usr/bin/codesign -T /usr/bin/security >/dev/null
security set-key-partition-list -S apple-tool:,apple: -s -k "$KC_PW" "$KEYCHAIN" >/dev/null
ORIGINAL_KEYCHAINS="$(security list-keychains -d user | tr -d '"' | xargs)"
security list-keychains -d user -s "$KEYCHAIN" $ORIGINAL_KEYCHAINS
restore_keychains() { security list-keychains -d user -s $ORIGINAL_KEYCHAINS; cleanup; }
trap restore_keychains EXIT

echo "==> Archive (build $BUILD_NUMBER)"
cd "$HERE"
xcodegen generate --quiet
xcodebuild -project BridgitPortal.xcodeproj -scheme BridgitPortal -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$WORK/BridgitPortal.xcarchive" \
  CURRENT_PROJECT_VERSION="$BUILD_NUMBER" DEVELOPMENT_TEAM="$TEAM_ID" \
  OTHER_CODE_SIGN_FLAGS="--keychain $KEYCHAIN" archive -quiet

echo "==> Export IPA"
xcodebuild -exportArchive -archivePath "$WORK/BridgitPortal.xcarchive" -exportPath "$WORK/export" \
  -exportOptionsPlist "$HERE/ExportOptions.plist" -quiet
IPA="$(ls "$WORK"/export/*.ipa)"
python3 "$REPO/tools/check-applet-bundle.py" "$IPA"

if [[ "$SKIP_UPLOAD" -eq 1 ]]; then
  cp "$IPA" "$HERE/build/BridgitPortal-$BUILD_NUMBER.ipa"
  echo "==> Skipped upload; IPA at $HERE/build/BridgitPortal-$BUILD_NUMBER.ipa"
  exit 0
fi

echo "==> Upload to App Store Connect and wait for processing"
APP_ID="$(asc apps list --output json | python3 -c 'import sys,json
for a in json.load(sys.stdin)["data"]:
  if a["attributes"]["bundleId"]==sys.argv[1]: print(a["id"]); break' "$BUNDLE_ID")"
[[ -n "$APP_ID" ]] || { echo "No App Store Connect app for $BUNDLE_ID yet: create \"$APP_NAME\" first." >&2; exit 1; }
asc builds upload --app "$APP_ID" --ipa "$IPA" --wait \
  --test-notes "Sign in with Google or pair with a Bridgit phone; try Home, Applets, Camera (photos for the phone's applets), Agents, More. Preview with sample data works without a phone." \
  --locale en-US --output json > "$WORK/upload.json"
BUILD_ID="$(python3 -c 'import sys,json; d=json.load(open(sys.argv[1])); print(d.get("buildId") or d.get("build",{}).get("id",""))' "$WORK/upload.json")"
echo "    build id: ${BUILD_ID:-unknown}"

if [[ -n "$BUILD_ID" ]]; then
  echo "==> Give the build to \"$GROUP\""
  # External group: --submit sends the build to TestFlight beta review (needed once per version).
  asc builds add-groups --build-id "$BUILD_ID" --group "$GROUP" --submit --confirm --output json >/dev/null \
    || echo "    Could not add the build to \"$GROUP\"; check beta review details in App Store Connect." >&2
fi
echo "==> Done: build $BUILD_NUMBER"
