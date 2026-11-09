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
# Prints "ACTIVE <id>" for a usable profile with this name, else "STALE <id>" for each one Apple has
# invalidated (it does that when the app's capabilities or certificates change). Apple refuses to create
# a profile whose name is already taken, even by an invalid one, so stale ones are deleted first.
# Without this, builds failed from Nov 2026 with "Multiple profiles found with the name".
profiles="$(asc profiles list --paginate --output json | python3 -c 'import sys,json
ps=[p for p in json.load(sys.stdin)["data"] if p["attributes"]["name"]==sys.argv[1]]
act=[p for p in ps if p["attributes"].get("profileState")=="ACTIVE"]
for p in act[:1] or ps: print("ACTIVE" if act else "STALE", p["id"])' "$PROFILE_NAME")"
profile_id="$(awk '$1=="ACTIVE"{print $2}' <<<"$profiles")"
if [[ -z "$profile_id" ]]; then
  for stale in $(awk '$1=="STALE"{print $2}' <<<"$profiles"); do
    echo "    Replacing invalid profile $stale"
    asc profiles delete --id "$stale" --confirm >/dev/null
  done
  profile_id="$(asc profiles create --name "$PROFILE_NAME" --profile-type IOS_APP_STORE --bundle "$bundle_res" --certificate "$CERT_ID" --output json | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')"
fi
[[ -n "$profile_id" ]] || { echo "Could not get an App Store profile named \"$PROFILE_NAME\"." >&2; exit 1; }
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

# Crash symbols (dSYMs) to Sentry, so crash and hang reports show file and line. Before Nov 2026 none were
# uploaded and every Apple stack was unreadable. Sentry's upload API takes a zip, so no sentry-cli is needed.
# The org is in Sentry's EU region, so uploads go to de.sentry.io. Token and org come from Bitwarden item
# "Sentry — Sponic Gardens"; any failure is reported, never fatal.
SENTRY_ITEM="cc10c985-fd97-46b8-aa97-b47b0136282e"
SENTRY_PROJECT="bridgit-portal-ios"
upload_dsyms() {
  local dsyms="$WORK/BridgitPortal.xcarchive/dSYMs" zip="$WORK/dsyms.zip" hdr="$WORK/sentry.hdr" org code
  [[ -d "$dsyms" ]] || { echo "    No dSYMs in the archive; skipped." >&2; return 0; }
  ditto -c -k --keepParent "$dsyms" "$zip"
  org="$( umask 077; "$BW" bw get item "$SENTRY_ITEM" | python3 -c '
import json, sys
f = {x["name"]: x["value"] for x in json.load(sys.stdin).get("fields") or []}
open(sys.argv[1], "w").write("Authorization: Bearer " + f["SENTRY_AUTH_TOKEN"].strip() + "\n")
print(f["SENTRY_ORG"], end="")' "$hdr" )" || { echo "    Could not read the Sentry token; skipped." >&2; rm -f "$hdr"; return 0; }
  code="$(curl --retry 3 --retry-all-errors --retry-delay 2 -sS -o "$WORK/sentry.json" -w '%{http_code}' -H @"$hdr" \
    -F "file=@$zip" "https://de.sentry.io/api/0/projects/$org/$SENTRY_PROJECT/files/dsyms/")" || code=000
  rm -f "$hdr"
  [[ "$code" == 2* ]] && echo "    dSYMs uploaded" || echo "    dSYM upload failed (HTTP $code)" >&2
}
echo "==> Upload crash symbols to Sentry"
upload_dsyms

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

# Update prompts (A0050): Bridgit Portal reads bridget/portal-ios-latest.json, next to the APK's latest.json, and
# asks the owner to update in TestFlight when its build is older. Written only once testers have the build. Uses
# the APK publisher's R2 settings (tools/publish.env) and token; without them it is skipped, never fatal.
publish_latest() {
  # shellcheck source=/dev/null
  [[ -f "$REPO/tools/publish.env" ]] && source "$REPO/tools/publish.env"
  if [[ -z "${BRIDGIT_R2_ACCOUNT:-}" || -z "${BRIDGIT_R2_BUCKET:-}" || -z "${BRIDGIT_R2_TOKEN_ITEM:-}" ]]; then
    echo "    No tools/publish.env R2 settings: update prompts won't see build $BUILD_NUMBER." >&2; return 0
  fi
  echo "==> Tell Bridgit Portal about build $BUILD_NUMBER (update prompts)"
  local version manifest hdr resp
  version="$(/usr/libexec/PlistBuddy -c 'Print ApplicationProperties:CFBundleShortVersionString' "$WORK/BridgitPortal.xcarchive/Info.plist" 2>/dev/null || echo "")"
  manifest="$WORK/portal-ios-latest.json"; hdr="$WORK/r2.hdr"; resp="$WORK/r2.json"
  python3 - "$BUNDLE_ID" "$version" "$BUILD_NUMBER" "$manifest" <<'PY'
import datetime, json, sys
bundle, version, build, out = sys.argv[1:5]
json.dump({"bundle_id": bundle, "version": version, "build": build,
           "published_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}, open(out, "w"))
PY
  ( umask 077; "$BW" bw get item "$BRIDGIT_R2_TOKEN_ITEM" | python3 -c '
import json, sys
d = json.loads(sys.stdin.read(), strict=False)
tok = (d.get("login") or {}).get("password") or next((f.get("value") for f in d.get("fields") or [] if "token" in (f.get("name") or "").lower() and f.get("value")), "")
if not tok: sys.exit("no token in the Bitwarden item")
open(sys.argv[1], "w").write("Authorization: Bearer " + tok.strip() + "\n")' "$hdr" ) || { echo "    Could not read the R2 token; skipped." >&2; return 0; }
  curl --retry 3 --retry-all-errors --retry-delay 2 -sS -X PUT \
    "https://api.cloudflare.com/client/v4/accounts/$BRIDGIT_R2_ACCOUNT/r2/buckets/$BRIDGIT_R2_BUCKET/objects/bridget/portal-ios-latest.json" \
    -H @"$hdr" -H "Content-Type: application/json" --data-binary "@$manifest" -o "$resp" || true
  rm -f "$hdr"
  python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); print("    bridget/portal-ios-latest.json:", "OK" if r.get("success") is True else "FAIL %s" % r.get("errors"))' "$resp" 2>/dev/null \
    || echo "    bridget/portal-ios-latest.json: FAIL (no reply)" >&2
}

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
  if asc builds add-groups --build-id "$BUILD_ID" --group "$GROUP" --submit --confirm --output json >/dev/null; then
    publish_latest
  else
    echo "    Could not add the build to \"$GROUP\"; check beta review details in App Store Connect." >&2
  fi
fi
echo "==> Done: build $BUILD_NUMBER"
