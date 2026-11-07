# Personal applet backend API

An instance is one applet on one device: `applet=dog` (the native Pet monitor) or `applet=pkg.<package id>` (an installed package applet, see below). Account aliases organize URLs, never authorization. Data and viewer grants remain scoped to the signed-in owner and the selected device/app instance. Device-only sends no applet data or remote AI calls. Gallery retention is seven days or newest 100 images (max 100 KB each, 640 px). Metrics are observed estimates and retained daily for seven days. No old local photos/history are backfilled.

## Control-agent setup (on the device)

Ask whether the user wants a backend portal for this applet on this device. If yes, ask for the portal email, desired alias, and whether to share photos; never guess these. Use the discovered owner API (requires its grant, an Idempotency-Key and approval for the exact action):

`POST /v1/owner/configure`

`{"action":"dog.configure","options":{"personal_backend":true,"backend_email":"owner@gmail.com","user_alias":"my-alias","photo_sharing":true}}`

Return the resulting `link_url` to the user. The device link code can be claimed only by that verified email; the browser still requires the user's explicit link action. The selected alias is saved after linking. An existing account or global remote-view setting never silently enables this applet backend. `personal_backend:false` stops uploads and remote AI, cancels active model requests, revokes viewers, and requests server deletion. Local history stays. Offline deletion is reported as pending until confirmed. `GET /v1/applets/dog-monitor` exposes consent, pending deletion, portal identity, state and metrics. Start/stop/camera/model/history owner actions use the same implementation as native controls. No permanent agent authority is granted by one approval.

## Installed package applets on the portal

An installed package applet (`bridgit-template/1`, adapter `android.package.v1`) that has a counting watch (`tally`) can get its own portal tab, for example the kitchen cleanliness monitor. Its applet id on the server is `pkg.<package id>` (`pkg.kitchen-cleanliness`), one per package per device. It follows the Pet monitor's rules: off by default, owner approval on the device, private to the signed-in owner and accepted viewers, 30-day retention, the same photo limits and storage allowance, rename and share from the portal, and portal deletion.

Ask the user first (portal email, alias, photo sharing), then request:

`POST /v1/owner/configure`

`{"action":"applet.configure","options":{"applet":"kitchen-cleanliness","personal_backend":true,"photo_sharing":true,"backend_email":"owner@gmail.com","user_alias":"my-alias"}}`

The result has `link_url` when the device still has to be linked to that account. `GET /v1/applets/installed` reports `web_portal` per applet. `personal_backend:false` (or removing the applet) deletes this applet's server data only; offline deletion is retried. The portal shows today's count per label, the latest answer with its time, the notices that fired today, the photo of the latest answered check (when photo sharing is on), and pause state; Pause on the portal pauses the applet on the phone. The device sends at most one summary a minute (when something changed, or every five minutes) and at most one photo a minute: `POST /v1/watch/applet/push` (device-signed), `{applet, timezone, summary, photo?, photo_at?}`. Each push also carries `records[]`, one per answered check and kind: `state` (the tracked answer, which makes the portal's day timeline and its 7- and 30-day patterns: share clean, messiest hour, time back to clean, longest clean stretch, best and hardest day) and `left_out` (when the applet's own card calls `POST /v1/images/find`, the found item names and boxes, with that photo while photo sharing is on). The record `kind` is open, so later kinds such as `person_seen` need no schema change; each record keeps only fixed words, numbers and boxes, and records follow the same 30-day retention and deletion. `POST /v1/watch/applet/summary` writes a 24-hour recap for a package applet from those computed numbers only (no photos). Portal reads use the routes above with `applet=pkg.<package id>`; the Pet monitor-only routes (episodes, capture, sampling) answer `invalid_applet`.

## Account routing and alias API

`GET /api/account/profile` reads the authenticated account's email and alias. `POST /api/account/profile {"alias":"my-alias"}` creates or changes its address. A verified HttpOnly email session or legacy Google bearer session is required; POST requires Origin https://bridgit.cc. Aliases are unique 3–30 lowercase letters/numbers/hyphens, start with a letter, and cannot use reserved site routes. Alias changes do not change any grants. Public aliases contain no profile data themselves. Private pages check the signed-in account, including when an alias exists but belongs to someone else.

Routes: `/my-alias` overview, `/my-alias/myapps` applets, `/my-alias/devices` linked devices. Legacy `/myapps` remains an entrance and sends a signed-in account to its chosen alias.

## Private web backend

Base: https://bridgit-feedback.bridgit-app.workers.dev. All paths below are under `/v1/watch`. Use the existing verified Google session bearer or verified email session. Browser email sessions use the same-origin `/api/watch/v1/watch/...` proxy; raw email session tokens are never returned to JavaScript. Every read checks authorization, including direct images. Responses are private/no-store.

| Method and route | Purpose |
| --- | --- |
| GET `/applets` | Owner instances and accepted viewer instances, with role and device name |
| GET `/applet?phone=<install>&applet=dog&day=YYYY-MM-DD` | Selected device day's metrics, separate latest live state, timezone and retention policy; omitted day selects Today in the device timezone |
| GET `/applet/gallery?phone=<install>&applet=dog&day=YYYY-MM-DD&before=<capture-ms>` | Newest-first image metadata; 25 per page; `next` cursor for older images |
| GET `/applet/image?phone=<install>&applet=dog&id=<id>` | Private JPEG, reauthorized on every request, never a public media URL |
| GET `/applet/shares?phone=<install>&applet=dog` | Owner-only pending/accepted viewer list |
| POST `/applet/shares?phone=<install>&applet=dog` `{"email":"viewer@gmail.com"}` | Owner-only email invitation, seven-day expiry, at most ten/hour and 20 viewers/pending invites per instance; repeat resends pending only |
| DELETE `/applet/shares?phone=<install>&applet=dog` `{"id":"<share-id>"}` | Owner-only immediate revocation, including pending tokens |
| GET `/applet/invite?token=<token>` | Matching verified recipient previews the exact applet/device; no access granted yet |
| POST `/applet/invite` `{"token":"<token>"}` | Matching verified recipient explicitly accepts a single-use invitation |

Viewer access covers images and metrics for that instance only, not generic activity, controls, other applets/devices, deletion, or re-sharing. Tokens are stored as hashes. Wrong/expired/revoked accounts and guessed object IDs fail closed. Revocation cannot erase copies a viewer already saved.

## App names, taglines and photos

`GET /applet/details?phone=<install>&applet=dog` returns `phone`, `applet`, `friendly_name`, `tagline`, and `avatar_data_url` for an owner or accepted viewer. Owner-only `POST` to the same route updates one or more fields in one atomic write:

```json
{"name":"Karock and Karoll","tagline":"A little window into their day.","image":"<raw base64 JPEG>"}
```

All fields are optional, but at least one must be present. Omitted fields keep their current values. `name` is 1–40 characters and unique among the owner's applet portals. `tagline` is plain text up to 160 characters; an empty string hides it. Names and taglines are trimmed and reject control characters. `image` follows the avatar limits below; `null` removes the photo. The entire update is rejected if any field is invalid, so text and photo changes are saved together. The JSON body limit is 46,000 bytes. Responses contain the actual saved details; `/applets` and `/applet` also include `tagline`. Existing applets start with “A little window into their day.”. The earlier `/applet/name` and `/applet/avatar` endpoints remain compatible.

Use the same watch-session authorization and same-origin cookie proxy described below. Viewer writes return 403, unavailable instances return 404, invalid fields return 400, duplicate names return 409, and oversized images/bodies return 413. In the portal, **Edit Details** opens the dedicated editor for name, tagline and photo; **Save changes** saves all edits together, while **Cancel** discards them.

## App thumbnail avatars

Each app instance has its own optional thumbnail, shared by its owner and all accepted viewers. Different instances (including those owned by different people) can use different photos. Only the owner can set, replace or remove it; viewers cannot customize or overwrite it. Removing backend consent or unlinking deletes the avatar with the instance. Avatars have no seven-day expiry and are separate from observation photos.

Authenticated API base: `https://bridgit-feedback.bridgit-app.workers.dev/v1/watch`. Use an existing watch-session bearer in `Authorization: Bearer <session>`. A device API key or feedback-agent key is not a watch session. Browser email sessions call the same routes through `https://bridgit.cc/api/watch/v1/watch` using their HttpOnly cookie and `Origin: https://bridgit.cc` on writes.

| Method and route | Purpose |
| --- | --- |
| GET `/applet/avatar?phone=<install>&applet=dog` | Owner or accepted viewer reads the thumbnail |
| POST `/applet/avatar?phone=<install>&applet=dog` | Owner sets/replaces it with JSON `{"image":"<raw base64 JPEG>"}` |
| DELETE `/applet/avatar?phone=<install>&applet=dog` | Owner removes it; no body required |

All three return `{"phone":"<install>","applet":"dog","avatar_data_url":"data:image/jpeg;base64,..."}`; a missing/removed avatar is `null`. `/applets` list entries and `/applet` details include the same `avatar_data_url` so clients can render private thumbnails without public image URLs. Responses are no-store and require current access. Revoked/unknown instances return 404; viewer writes return 403. Copies already saved cannot be recalled.

Uploads must be JPEG, at most 256 pixels on either edge and 32 KB decoded, with a JSON body of at most 45,000 bytes. Do not send data-URL prefixes, image URLs, SVG or arbitrary MIME types. Invalid images return 400; oversized bodies/images return 413 without changing the saved avatar. The portal’s **Edit Details** photo field accepts JPEG/PNG/WebP up to 10 MB, crops the center to a square, and re-encodes to a bounded JPEG thumbnail before upload.

## Device-to-server operations

The install signs these requests with its existing Keystore identity. Browser or agent credentials cannot impersonate an install.

- POST `/v1/watch/link-code` accepts `phone_name`, optional expected `email` and `user_alias`. The code expires after ten minutes; wrong verified email does not consume it.
- POST `/v1/watch/applets/backend {"applet":"dog","enabled":true,"since":<consent-ms>}` enables only that instance after verified linking. False deletes that instance's server images, metric snapshots and shares. Old remote-view consent is insufficient.
- POST `/v1/watch/dog` accepts the bounded sanitized summary, timezone, optional small JPEG, capture timestamp and matching reading/camera metadata. The client gates before serialization/queueing. The server rejects missing consent and stale photos, enforces capture interval and retention, and guards writes against a stale consent generation.


## Owner overview and summary portals

Remote view status now includes bounded hardware specs (model, Android, RAM and storage), transport connection states, external-service readiness, agent last-use/paused state, and a metadata-only inventory of Pet monitor, the current tour and active watches. No addresses, keys, raw readings, watch questions/rules, coordinates or images are included. These fields are sent only with the existing Remote view consent, not merely because a Pet monitor personal backend is enabled. Older clients continue to work; missing or stale fields remain unknown. HTTP agents and external request/response services are described by recent use/readiness, not a persistent socket connection.

An owner can explicitly create a private **summary portal** with `POST /v1/watch/overview/portal` and `{phone, applet}`. The app id must occur in that owned device's reported inventory. This is an idempotent presentation of already-shared metadata; it does not enable the personal backend, start an app, make AI calls, enable photo history or grant viewers. Summary portals disappear with the device link. Pet monitor's existing personal backend and its per-device consent flow remain separate. Existing personal portals retain their sharing and richer history.

## Delete Web Portal

Owners can call `DELETE /v1/watch/applet/details?phone=<install>&applet=dog` with JSON `{"confirm":true}` (or the same-origin cookie proxy). The confirmation is mandatory. Viewers cannot delete portals. The atomic operation removes this instance's photos, metrics, avatar, tagline/name and viewer grants, returning `{"deleted":true,"phone":"<install>","applet":"dog"}`. It preserves device linking, account authentication, local device history, other portals and device-wide activity. The website's **Edit Details → Delete Web Portal** requires a second explicit confirmation and returns to the overview after deletion. Cancel sends no deletion request.

A minimal install/app deletion marker prevents old or offline clients from recreating it on a stale consent timestamp. Signed device heartbeats return `dog_portal_deleted:true` until new consent. Existing uploads and stale backend-enable calls are rejected. Updated devices stop Pet monitor, uploads and remote AI at their next server contact; offline deletion cannot immediately stop a disconnected device. Explicitly choosing a new portal on the device supplies fresh consent and starts an empty portal without restoring old grants/history.

Android uses **Open Web Portal** for navigation and **Delete Web Portal** for the confirmed destructive action. When initiated offline on Android, uploads and monitoring stop locally first and the existing narrow deletion request remains pending until acknowledged.

## Camera and microphone sources on the device

The owner-approved `POST /v1/owner/configure` action `dog.configure` accepts either legacy `camera` (`auto`, `rear`, `front`, `lan:<id>`) or `cameras:["rear","front","lan:<id>"]`. The array is a manual selection whose cameras take turns at the existing photo interval, rather than increasing capture frequency. Auto is exclusive and resumes testing eligible cameras. The native screen lists every enrolled network camera individually and uses checkboxes; devices not assigned to Pet monitor remain disabled until the owner changes Device access.

Optional `microphones:["builtin","lan:<id>"]` selects audio sources; `[]` disables audio, and the initial default is empty. During monitoring, each chosen source is sampled for five seconds: a camera microphone whose loudness stream fails about every 10 minutes, and the food-view camera every sampling interval (default 20 seconds; `sampling.all_s` and per-camera values in the dog-monitor status, set with `dog.configure` `sampling_all_s` or from the web portal). Clips go to Clef for estimated barking/growling/eating/drinking/other/unclear classification and are discarded; this is sampled coverage, not continuous listening or a safety system. Failures are reported as unavailable, never silence. The device shows the current microphone state; the authenticated dog-monitor status exposes `microphones`, `microphone_status`, and recent bounded `audio_readings` (source, time, sound, confidence).

Built-in microphone use requires Android audio permission and the owner microphone switch. Network audio requires assigned camera access and the separate `lan_camera_audio` and `lan_camera` switches. Agent configuration/read access also needs the relevant microphone grants. Native microphone checkboxes enable their corresponding owner switches explicitly; agent calls cannot silently enable them. Monitoring stop, portal deletion and unchecking sources cancel audio sampling. Neither audio clips nor microphone results are added to the web gallery by this change.

## Pause and resume

Owner-only `POST /v1/watch/applet/pause?phone=<install>&applet=dog` with `{"paused":true}` pauses Pet monitor; false resumes it. Edit Details offers both actions. Photos, metrics, details, consent and viewer grants are retained under the existing retention policy. The response and applet reads include `paused`, `pause_pending`, and `pause_supported`. Pending remains true until the signed device acknowledges the current command revision and consent generation. Older devices receive update guidance; they cannot claim to apply pause. Devices poll every 30 seconds while the personal backend is enabled, stop monitoring and model calls on pause, and apply resume once without resetting history. Offline changes remain pending.

Pet monitor describes **Resting**, rest periods and resting time throughout Android, the portal and the current template. Model questions no longer infer sleeping. Historical `sleeping` readings display and accumulate as resting. Persisted/API metric keys (`sleep_ms`, `naps`, `asleep_since`, `asleep_for_ms`) remain compatible and now describe observed resting; saved history is preserved.


### Food-bowl view and sound-triggered checks

In the native Pet monitor screen, **Food-bowl view & eating sounds** saves an enrolled camera’s current normalized ONVIF pan/tilt position and explicitly enables that camera’s microphone. Cameras must report a usable absolute position; relative nudges are never accumulated. Setup is owner-operated on the device. The authenticated monitor status includes `feeding: {enabled, camera, view_saved, status}` without credentials or coordinates.

A fresh eating/drinking estimate from the saved camera at at least 75% confidence queues a check in the existing serial photo loop. The camera must reach its saved position and acknowledge Stop before photos are classified afresh for 30 seconds. Triggers have a 60-second cooldown. Access is checked before motion, after settling and before capture; a changed enrollment requires saving the view again. Existing app/device permissions and the global motion lease still apply. Turning the response off does not remove separately selected microphones.

Five-second samples with a minute between rounds can miss short events. Audio identifies neither a particular dog nor a confirmed meal. Only the subsequent visual classification contributes to meal metrics. The feeding response starts disabled and its enablement, camera name and status remain visible in Pet monitor.
## Hourly photo emails for permitted users

Owners can POST to `/v1/watch/applet/shares?phone=<install>&applet=dog` with `{"id":"<share id>","email_photos":true}` (or false). GET includes `email_photos` on each grant. Preferences are scoped to the instance and grant; viewers cannot change other users’ settings. Existing grants default off. Pending invitations must be accepted first. Revoking a grant, deleting the portal, or disabling the backend stops future delivery.

The existing half-hour scheduler considers photos through the last completed UTC clock hour. A per-viewer cursor carries forward history if scheduling or the strict rolling-hour cap delays a digest, so intervening hours are not silently skipped. It includes only retained gallery photos captured since opt-in. Clef rates visual quality (focus, exposure, framing, clearly visible pet), separately from pet-detection confidence; only a high-quality probability of at least 0.85 is included. Ratings are cached with the existing image and share the install’s normal model budgets. Empty/uncertain/error-only hours send nothing. All qualifying photos are included chronologically as private inline JPEG attachments, with times/activity/camera labels and a portal link. The existing private R2 storage and 30-day retention remain unchanged. Each digest processes up to 120 photos (one full hour at the maximum capture rate); any delayed backlog carries forward rather than being discarded.

A database claim deduplicates each viewer/hour, and an atomic rolling-hour send reservation prevents sends less than 60 minutes apart. Delivery failures are recorded and not repeatedly mailed; the next hour can try again. Every email has a token-protected stop-email form; GET only displays the confirmation, POST stops photo emails without revoking portal access. No live subscription is enabled as part of deploying this feature.

### Photo episodes

The owner can `POST /v1/watch/applet/episodes?phone=<install>&applet=dog` to enable
reversible organization of all retained photos. Each call processes a bounded batch;
background processing resumes every two minutes and includes new uploads. Gallery
responses report `organization: {total, processed, episodes}`. The original photo
and device reading are preserved; organized rows carry a derived `episode` and
`original_reading`, with corrected activity in `reading`. The original dog-confidence
score is not attributed to the new model. Live readings and activity-duration totals
remain device observations; the corrected labels describe saved photo history.

Fresh model checks alone do not split episodes. The model compares each new visual
scene with the preceding sample and episode representative; existing valid per-camera
visual-continuity markers reuse that assessment. Changed activity/location, uncertain
assessments, camera changes and gaps over 90 seconds stay separate. The portal then
shows these episodes as a compact timeline: consecutive episodes from the same camera
merge into one row when their activity matches (or one side is unclear), the place
matches and samples are at most 15 minutes apart. Each row shows one small
representative image, the time span, the photo count and any unclear frames or time
without photos; gaps of 5 minutes or more between rows are shown. "View all" opens
that row's saved sequence. Durations are sampled spans, not proof of continuous
activity. Resting is not confirmed sleep.

## Terminology and compatibility

Use **applet** for an internal Bridgit tool such as Pet monitor or City tour; use **app** for Bridgit itself or installed Android software. The account navigation is **My applets**; its existing `/myapps/` links remain valid. Batch sharing at `POST /v1/watch/applet/shares/batch` accepts `{email, applets:[{phone,applet}]}`. Legacy `apps` is accepted; if both fields are sent they must agree. Device assignment uses `GET/POST /v1/applet-devices`; `/v1/app-devices` remains an authenticated compatibility alias under the same existing `app_device_access` grant. Historical identifiers and immutable published template revisions remain valid.

Device inventory accepts canonical `applets` membership and legacy `apps`. New device uploads include both for older backends; the backend sanitizes both to supported applet IDs. Existing stored inventories remain readable.

## Applet settings links and browser settings

`https://bridgit.cc/applet-settings/?device=<install>&applet=<dog|pkg.id>` selects one device and applet. Android App Links and Apple Universal Links open the installed app; without it the route redirects to the authenticated HTML settings at `/myapps/?device=<install>&screen=settings#<applet>`. Android also accepts `bridgit://settings` and Apple `bridgitportal://settings` with the same parameters. Links never carry a session or confer authority. The portal gear uses the associated `www.bridgit.cc` host on Apple devices so Safari can hand off across hosts. Both hosts fall back to the same authenticated HTML page. The HTML screen also offers **Open in app**. Existing portal and sign-in URL associations are preserved.

The shared portal's settings gear replaces Services. Details, Setup and Services use the selected Lilac frost standard; **View portal** returns to the applet. Name/tagline use owner-only `POST /v1/watch/applet/details` (GET reads; existing DELETE deletes the portal), pause uses `/v1/watch/applet/pause`, and Pet monitor sampling uses `/v1/watch/applet/sampling`. Existing validation and pending-device acknowledgments apply. This does not migrate every native settings screen; the design guide records that migration target.

Owner-only `GET /v1/watch/applet/budget?phone=<install>&applet=<id>` returns the current budget projection. `POST` on that URL with `{ "limits": [{"id":"day","per":"day","usd":1,"check_at":[0.5]}] }` replaces limits through the existing canonical budget validator and audit/revision path. Periods: hour/day/week/month; 1–10000 cents per limit; at most eight limits. Empty limits clears them. The budget remains shared by that applet across the owner's devices. Non-owner viewers cannot read or write this endpoint. Reads are also included in the applet's existing owner projection; the device consumes changes through its existing signed budget sync. Browser email-code sessions proxy through `/api/watch`; Google sessions use the same backend APIs. Navigation itself introduces no editable preference.
