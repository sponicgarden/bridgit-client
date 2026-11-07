# Bridgit Portal (iPhone, iPad and Mac Catalyst)

A native SwiftUI viewer for Bridgit phones. It shows the APK's screens: Home (the APK's own
diagnostics scene and its animations), Applets, Agents, Devices (network devices), and More
(Activity, Feature enablement, About this phone, pairing, account).

Signed-in owners can open Applets › Library › Browse applet library, search the
registry and page through results. Opening an entry downloads its exact release.
The portal verifies the registry key, revision, package id and asset checksums,
then shows the package version, requested permissions and flows. Save package
exports a self-contained JSON file to Files for offline use.

### Running an installed HTML card

Choose **Open applet card** in the local edition to run the HTML asset named by
`ui.card.asset`. Each card opens in an ephemeral WebKit view. Calls through
`await Bridgit.act({method,path,body,query})` are dispatched to a local handler.
For example, Fairytale reads and saves its history with GET and POST on
`/v1/applet/tables/stories/rows`. A package needs the `applet_data` permission
and a matching table declaration. Read pages oldest first, passing `next` as
`query.since` until the result has `next: null`.

For a reviewed portrait, declare `camera` and `captures`. The foreground card
calls POST `/v1/camera/snapshot` with `viewfinder:true`, `store:["memory"]`,
`return_image:false` and a `front` or `rear` camera. The optional
`countdown_seconds` (0–10), `max_edge_px` (64–1280) and `quality` (1–100)
control the shutter and JPEG. The owner taps **Take photo** and **Use photo**.
Read a preview with GET `/v1/captures/{capture_id}` and remove it with DELETE.
Closing the card clears its captures.

For a surroundings clip, also declare `video` and POST `/v1/camera/video` with
`viewfinder:true` and `extract_frames` (1–8). Apple supports silent 720p
recording, a front or rear camera, a 0–10 second countdown and a 1–30 second
duration. The owner starts recording, can finish early, and can play, retake or
accept the video. The result contains `video_capture_id` and an ordered
`frames` array with one `capture_id` per JPEG. To play the clip again, POST its
`capture_id` to `/v1/camera/video/review`. If the owner deletes it there, the
response has `deleted:true`. Delete any remaining frame IDs separately.
Declare `location` to request GET `/v1/location`. The host names the applet
and asks the owner to allow one reading. The OS location setting determines
the reported `precision` (precise or approximate). The default requests a new
fix; use `query.fresh:false` for a cached one or `query.accuracy:"balanced"`
for a lower-accuracy request. Read `age_s` and `accuracy_m` with the
coordinates. The Apple host omits compass readings. The call is local.
For nearby places or air quality, declare `judgment` and call POST
`/v1/services/context` with `service:"places"` or `service:"airquality"`
and numeric `latitude` and `longitude`. The host asks separately before
sending coordinates to each named Google service. The signed request uses the
installed edition’s own budget. Read `data`, retain `attribution`, and follow
the returned `data_use` guidance. For story generation, declare `judgment` and `captures`, then call POST
`/v1/story/generate` with `mode` (`kind` or `wild`), two to eight
`frame_capture_ids`, one `portrait_capture_id` and optional `context`. IDs
belong to the open card. The owner approves sending the photos and context to
GLM 5.3 Flash via OpenRouter. The service inventories the images, then writes
the story. Save `title`, `narration` and `mode` through the card’s table API.
The host supplies a `presentation_token` and POST `/v1/story/stop` cancels an
in-flight story and stops the card’s audio. A card with `speaker` permission
can play an MP3 explicitly referenced in its HTML from
`https://bridgit.cc/assets/audio/` using POST `/v1/speaker/play`.
For narration, obtain the current `presentation_token` from the generation
result or start a replay with POST `/v1/story/presentation`. Resolve the
selected voice with GET `/v1/speech/models` before sending text. POST
`/v1/speech/speak` with the token, `expected_model`, `allow_cloud:true`,
`language:"en"`, `replace:"queue"` and up to 600 characters of text. The owner
approves the named voice once per reading, up to 3,200 characters. GET
`/v1/speaker` reports playback and queued chunks. Stopping the presentation,
closing the card or backgrounding it clears the queue. Fairytale requires Aura 1.
For its illustration, call POST `/v1/story/image` with the active presentation
token, story title and narration, and a stable `request_id` (8–128 letters,
digits, dashes or underscores). Declare both `judgment` and `interactions`.
After the owner approves, the host displays the JPEG locally and returns
display status and quota to the card. Reuse the same ID and story on a retry.

## Where the data comes from

- **Live, through the relay.** The phone's owner grants this device access on the phone; the app
  gets a relay connector pairing (`rly_` token) as a QR code, a `bridgitportal://pair#…` link, or the
  phone's More › Relay setup text. Calls go to `POST <relay>/d/<device>/jobs?wait=N`
  (`bridgit-cloud/relay/README.md`). It prefers the read-only `/v1/portal/*` routes (`docs/ios-portal-api.md`) and
  falls back to `/v1/health`, `/v1/capabilities`, `/v1/log` and `/v1/lan/discovered` on phones
  without them. Refreshes are spaced to the relay's per-pairing allowance.
- **Account snapshot.** Signing in with Google uses the same account as bridgit.cc/myapps. The app
  opens bridgit.cc/portal-signin/ in a system web sheet; that page gets a `bws_` session from the
  feedback Worker and hands it back with `bridgitportal://signin#session=…`. The app then reads
  `/v1/watch/phones`, `/events` and `/applets*` (shared applet portals included). Phone data here is
  the remote-view snapshot, up to a few minutes old, and is always labelled with its age.

The custom-scheme hand-off is the v1 trade-off: another app could register the same scheme, so the
page never hands over a stored session without a tap. Universal Links would remove that, and need
the Apple team ID.

For a local Mac Catalyst build and hosted tests, use the same project and scheme
with destination `platform=macOS,variant=Mac Catalyst`.

## Build

```bash
brew install xcodegen
cd ios/BridgitPortal && xcodegen generate
xcodebuild -project BridgitPortal.xcodeproj -scheme BridgitPortal -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

The Home scene files are copied from `app/src/main/assets/diagnostics/` at build time, so the iOS
app always shows the same scene as the APK. Launch with `-sampleData` (or tap "Preview with sample
data") to see every screen without a phone. Tests: `xcodebuild test … -scheme BridgitPortal`.

**Install on this device** keeps the selected registry revision and its initial
configuration in the Apple host’s SQLite library. Reopen **Applets › Library ›
On this device** offline while signed in to the same account. To update, select a newer revision in the catalog, review the permission and remote-service changes, and confirm. The host preserves saved settings and fills missing settings from the new defaults. An incompatible setting blocks the update. Swipe an edition
to remove its local copy. Cards can show text, call a named flow from a button, refresh, and display the
latest rows from a local table. Foreground flows can ask the owner a timed
multiple-choice question, record text, choices, numbers and booleans in a
declared table, show a notice, wait or stop. The library scopes rows to the
installation and removes them on uninstall. Closing the screen cancels a run.
The Sol soil journal in the authoring trials exercises this path. Camera,
provider calls, background watches and more advanced card operations still
require additional host support.
