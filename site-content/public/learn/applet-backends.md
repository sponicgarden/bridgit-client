# Personal applet backend API

An instance is one applet on one device: `applet=dog` (the native Pet monitor) or `applet=pkg.<package id>` (an installed package applet, see below). Account aliases organize URLs, never authorization. Data and viewer grants remain scoped to the signed-in owner and the selected device/app instance. Device-only sends no applet data or remote AI calls. Gallery photos live in private per-user R2 buckets, with 1 GB free storage shared across the account's devices. Photos expire after 30 days (max 100 KB each, 640 px); the portal reports the account's actual allowance and usage. New history pauses when the allowance is full. Metrics are observed estimates and retained daily for 30 days. No old local photos/history are backfilled.

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

`GET /api/account/profile` reads the authenticated account's email and alias. `POST /api/account/profile {"alias":"my-alias"}` creates or changes its address. A verified HttpOnly email session or legacy Google bearer session is required; POST requires Origin https://bridgit.cc. Aliases are unique 3–30 lowercase letters/numbers/hyphens, start with a letter, and cannot end with a hyphen or use reserved site routes. The website's profile API enforces the reserved-route and trailing-hyphen rules; a `user_alias` sent with a device link code is only checked against the 3–30 character pattern, so choose an alias that also passes the website rules. Alias changes do not change any grants. Public aliases contain no profile data themselves. Private pages check the signed-in account, including when an alias exists but belongs to someone else.

Routes: `/my-alias` overview, `/my-alias/myapps` applets, `/my-alias/devices` linked devices. Legacy `/myapps` remains an entrance and sends a signed-in account to its chosen alias.

## Private web backend

Base: https://bridgit-feedback.bridgit-app.workers.dev. All paths below are under `/v1/watch`. Use the existing verified Google session bearer or verified email session. Browser email sessions use the same-origin `/api/watch/v1/watch/...` proxy; raw email session tokens are never returned to JavaScript. Every read checks authorization, including direct images. Responses are private/no-store.

| Method and route | Purpose |
| --- | --- |
| GET `/applets` | Owner instances and accepted viewer instances, with role and device name |
| GET `/applet?phone=<install>&applet=dog&day=YYYY-MM-DD` | Selected device day's metrics, separate latest live state, timezone and retention policy; omitted day selects Today in the device timezone |
| GET `/applet/gallery?phone=<install>&applet=dog&day=YYYY-MM-DD&before=<capture-ms>` | Newest-first image metadata; 25 per page, or 250 when episode grouping is on; `next` cursor for older images |
| GET `/applet/image?phone=<install>&applet=dog&id=<id>` | Private JPEG, reauthorized on every request, never a public media URL |
| GET `/applet/shares?phone=<install>&applet=dog` | Owner-only pending/accepted viewer list |
| POST `/applet/shares?phone=<install>&applet=dog` `{"email":"viewer@gmail.com"}` | Owner-only email invitation, 30-day expiry, at most ten/hour and 20 viewers/pending invites per instance; repeat resends pending only |
| DELETE `/applet/shares?phone=<install>&applet=dog` `{"id":"<share-id>"}` | Owner-only immediate revocation, including pending tokens |
| GET `/applet/invite?token=<token>` | Matching verified recipient previews the exact applet/device; no access granted yet |
| POST `/applet/invite` `{"token":"<token>"}` | Matching verified recipient explicitly accepts a single-use invitation |

Viewer access covers images and metrics for that instance only, not generic activity, controls, other apps/devices, deletion, or re-sharing. Tokens are stored as hashes. Wrong/expired/revoked accounts and guessed object IDs fail closed. Revocation cannot erase copies a viewer already saved.

## Device-to-server operations

The install signs these requests with its existing Keystore identity. Browser or agent credentials cannot impersonate an install.

- POST `/v1/watch/link-code` accepts `phone_name`, optional `device_type` (`phone`, `tablet` or `device`, the default), optional expected `email` and `user_alias`. The code expires after ten minutes; a new code replaces any open one; at most ten codes per install per hour (then 429 `rate_limited`). Wrong verified email does not consume it.
- POST `/v1/watch/applets/backend {"applet":"dog","enabled":true,"since":<consent-ms>}` enables only that instance after verified linking. `enabled:false` deletes all of this install's dog data on the server: images, captures, observations, day metrics, shares, the backend record and the live dog summary. Old remote-view consent is insufficient.
- POST `/v1/watch/dog` accepts the bounded sanitized summary, timezone, optional small JPEG, capture timestamp and matching reading/camera metadata. The client gates before serialization/queueing. The server rejects missing consent and stale photos, enforces capture interval and retention, and guards writes against a stale consent generation.

### Camera search timing and similar moments

In Pet monitor Auto, every camera defaults to a 60-second search delay. Configure it in **Camera search delays** on the device, or request an owner-approved `dog.configure` with `camera_search_delays:{"rear":60,"front":120,"lan:example":90}`. Values are whole seconds from 10 to 3600; `null` restores 60. Settings are stored on this device per application and sensor, so another application or device keeps its own values. Manual camera choices keep taking turns and do not search automatically. With multiple active views, each must have missed the pet for its own delay before Auto searches; searches repeat after those delays while no view finds the pet. Processing and capture time can delay a scan. `GET /v1/applets/dog-monitor` reports `camera_search_delays` and `camera_search_delay_default_s`.

New readings include `unchanged_since` (milliseconds): the beginning of the device’s current visually similar scene for that camera. The private gallery shows the first and latest sampled pictures of each consecutive matching scene, joined by its elapsed time. It does not infer continuity from matching activity labels. Changes, camera/model switches, missing markers, and gaps over 90 seconds split groups. Photos remain subject to the existing confidence filter, retention and private access rules.
