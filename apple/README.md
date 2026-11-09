# Bridgit for iPhone, iPad and Mac

One XcodeGen project, two native applications, and a shared Swift package.
iOS uses `cc.bridgit.app` (Debug `cc.bridgit.app.debug`) and only `bridgit://`
links. This is a clean installation identity; old app credentials are not migrated.
macOS remains a local app with its separate `cc.bridgit.macos.local` identity.

## Local development

```sh
cd apple
xcodegen generate
xcodebuild -project Bridgit.xcodeproj -scheme Bridgit-iOS -showdestinations
# Choose an installed simulator UUID from the output:
xcodebuild -project Bridgit.xcodeproj -scheme Bridgit-iOS -destination 'platform=iOS Simulator,id=<UUID>' build test
xcodebuild -project Bridgit.xcodeproj -scheme Bridgit-macOS -destination 'platform=macOS' build
(cd BridgitCore && swift test)
```

`BridgitCore` contains the existing relay transport, pairing/claim parsing and
caller-filtered device profile models. It imports no UIKit or AppKit. `Shared/`
contains cloud models/client and Keychain storage compiled in each application;
platform-specific sign-in presentation, image conversion and views stay in their
own targets. iOS copies Home scene assets, sample cards and device brands from
the same sources as Android. Launch iOS with `-sampleData` for explicit sample
screens. Add `-showThisDevice` to open local facts, or open `bridgit://device`. The Mac app never fabricates linked-device data.

## Foundation scope

These local builds include device-local applets, an inbox and scoped relay hosting.
A linked-device view does not grant host or sensor authority. Main and satellite
are link roles; available operations depend on actual device capabilities and
owner/caller grants. Applets uses Pages / Config / Cards; Library stays under Cards.
Only `bridgit://pair`, `bridgit://signin` and the explicit local-device route are
accepted. Legacy schemes and aliases are not supported.

Parity remains incomplete: general applet workflows/watches, configured instances,
satellite forwarding and the full Android sensor/control catalog still require
implementation and acceptance. See the coordination journal for current evidence.

## Agent feedback on this device

Paired control agents can file, list and follow up on their own reports through
the relay or the direct Tailscale listener. Viewer, satellite and applet
principals have separate access. MCP uses the same operations after its
`device.read` handshake.

- `POST /v1/feedback` files a bug or feature with a description and an
  Idempotency-Key. The device supplies its own redacted recent Activity.
- `GET /v1/feedback` filters by `mine`, `status`, `kind` and ISO `since`.
  `limit` is 1–100 and defaults to 50. With `since`, `wait` is 0–55 seconds,
  with four open feedback waits per agent.
- `GET /v1/feedback/{id}` returns the filing agent’s text and history.
  `POST /v1/feedback/{id}/followup` accepts a note or an eligible `works` or
  `still_broken` outcome. The filing agent is identified by its persisted ID.
- The owner can grant **Fixer Mode** in Control agents. An existing agent
  administrator can include or remove `feedback.fixer` in the target agent’s
  complete `operations` array using `PATCH /v1/agents/{id}`. Agent reads expose
  that grant. Fixer Mode can view all reports with attachments and Activity,
  file with up to five images and triage with `POST /v1/feedback/{id}/status`.
- `GET /v1/feedback/team-key?rotate=true` returns or rotates only the caller’s
  own team key while feedback sending is enabled. The key is stored in Keychain
  and is excluded from Activity. The device queues retirement when the associated
  control agent is removed, paused or changes generation or Fixer access.

Reports saved while sending is off remain local. The authenticated settings API
exposes and changes `feedback_upload` through `GET /v1/settings` and
`POST /v1/settings`. Direct HTTP can return the full saved detail. A large relay
or MCP reply can require `size=thumb`. Authenticated direct report filing accepts
up to 2 MiB of JSON, including the MCP request envelope. Relay filing remains
subject to the Apple 1 MiB body limit and relay frame size.

## Local host Activity

Direct sign-in refusals and rejected HTTP requests with a parsed request line
appear in Activity. Only a verified bearer and allowed source can attribute an
agent. The log retains a known API template or an unknown-route marker and
excludes credentials, body, query and arbitrary URL text. A request with no
recognized route returns 404. A recognized operation unavailable on the direct
listener returns 501. Raw dropped connections and input without a reliable
request line have no endpoint audit row.

The `log` grant, shown as **Read Activity and write notes** in the owner’s agent setup,
allows `POST /v1/log` and authenticated Activity reads. Supply a nonblank `message` of up to 1024 UTF-8 bytes,
optional `level` (`debug`, `info`, `warn`, or `error`, default `info`), and
optional JSON object `data` of up to 4096 UTF-8 bytes. Success returns HTTP 201
with `{"ok":true,"channel":"agent"}`. A rolling limit of 60 notes per agent
per minute returns HTTP 429 with `Retry-After`.

Send an optional `Idempotency-Key` of 8–128 ASCII letters, digits, underscores,
or hyphens to protect retries. Reuse it only for the same request. The same
operation is advertised in HTTP discovery and OpenAPI and as MCP `log_note`.
The owner can configure the grant through the authenticated agent configuration
routes. Notes are attributed to the authenticated agent and redacted before
storage. Activity detail and JSONL export include redacted structured data.
CSV keeps the pinned log columns and includes the note’s severity and message.

Controllers with the current `log` grant can read device-local Activity through
the relay. MCP also requires the existing `device.read` session grant. The owner sets **Agents with Activity access can read** to All Activity
(the default) or Their own Activity. Own visibility checks the authenticated
agent ID, so historical rows without a stored ID are outside that scope.
Administrators retain all-Activity access. The same policy is available as
`log_visibility` (`all` or `own`) through `GET /v1/settings`,
`POST /v1/settings`, and `DELETE /v1/settings/log_visibility` to reset. Changes
from agents use the authenticated settings grant and acknowledged request flow.

- `GET /v1/log` or MCP `log_list` reads a cursor page. Filters include channel,
  kind, agent (including `me`), minimum level, endpoint prefix, result, literal
  text search, and ID or ISO time boundaries. Tail mode uses `order=asc`, an
  ID-valued `since`, and `wait=1…55`.
- `GET /v1/log/{id}` or MCP `log_entry` reads one visible row.
- `GET /v1/log/stats` or MCP `log_stats` aggregates actual request events by agent
  and endpoint, defaulting to the last 24 hours. Measurements absent from old
  rows remain null. A group without any measured response bytes reports null.
- `GET /v1/log/export` or MCP `log_export` exports all matching rows in ascending
  order as `jsonl` (default) or `csv`. The sealed relay result, including encoding
  and MCP text escaping, must fit in 8 MiB. Oversize results return an error.
  Narrow the time range or filters, or use cursor pages for larger reads.

Request facts include the executed method and route, host processing time, and
the host's response body size before transport. Input bytes are present only
where the transport measures them. Authenticated identity, including current
name where available, is independent of request parameters.

The owner’s direct Tailscale listener also serves Activity and streams JSONL or
CSV without a total-size cap. Direct export uses HTTP/1.1 chunked framing.
An interrupted transfer omits the final chunk, so clients should require
complete HTTP framing before accepting a file. The relay shares its two-job
host ceiling with Activity tail requests, whereas Android admits four
concurrent waits per agent. Apple rejects negative numeric cursors and IDs.

## Direct Tailscale control agents

In **Add control agent**, select **Yes, use Tailscale**. Choose an address
assigned to this device, start the direct listener, and review the agent’s
grants. This listener uses port 8734 and stays separate from relay enrollment
and satellite pairing. The setup shows the new bearer key once. The first
connection binds the key to its source device. The agent’s controls show
allowed addresses and whether new device arrivals were allowed or blocked.
The owner can approve another address, dismiss the alert, or unlock the
agent so the next connection establishes a new binding.

The settings grant exposes `host_direct_enabled`, `host_direct_address`, and
`host_direct_device_lock` through `GET /v1/settings` and the usual acknowledged
write/reset routes. The settings response separately reports the preferred
address, candidates, bound address, port, and listening status. Changing the
preferred address selects the address for the next start. The administrator
can update an existing direct agent’s `owner_direct_peers` with
`PATCH /v1/agents/{id}`; an empty list unlocks it. No API returns stored keys.

The owner can rotate a control agent’s key and share its one-time replacement
setup from Control agents. Rotation preserves the agent ID, grants, and pause
state, invalidates the previous generation, and clears its direct device
binding. An administrator can rotate a different existing control agent using
`POST /v1/agents/{id}/rotate` with an `Idempotency-Key` header and a JSON
`token_hash` containing the lowercase SHA-256 of a fresh key. The administrator
generates and supplies the replacement key separately. The response contains no
credential. The direct listener returns 401 with `reason: key_rotated` or
`reason: agent_removed` for seven days. Relay currently returns its generic
unauthorized response for a retired key.

The owner can open a control agent’s details to edit its name, notes, grants and
applet scope. A new name starts with a letter or digit and uses up to 24 ASCII
letters, digits, spaces, hyphens or underscores. Names are case-insensitively
unique. A different authorized administrator can update the same fields
with `PATCH /v1/agents/{id}`. The administrator roster includes `notes`; the
agent’s self profile omits them. Empty notes clear the field, and saves reject
notes longer than 200 UTF-16 code units. Notes are excluded from Activity.
In Agents, **Turn off device capabilities** disables the host camera and
microphone clips, files, shares, downloads, cloud appraisals, agent LAN
discovery and Internet callback destinations. Applet-specific settings, the host's listener and saved agent
grants are independent of this action.

Agents with the settings write grant can send
`{"field":"device_capabilities_off","value":true}` to `POST /v1/settings`
or `settings_change` over MCP. The action applies after the queued response
is delivered and the request's authority is rechecked. Poll the returned
request URL for completion. Settings reads report the exact switches in
`device_capabilities_off_fields` and their individual values. Enable a
capability using its own setting and any required OS consent.

**External calls** in Agents pauses new Bridgit network requests for the
account, library, relay, feedback, cloud models, Connections and active LAN
scans. It preserves saved settings, remembered LAN data, direct host access
and eligible Tailnet callbacks. The relay reconnects when external calls are
resumed if its host setting remains on. Data already sent cannot be recalled.

Read `external_calls_paused` with `GET /v1/settings`, change it with
`POST /v1/settings`, or resume with `DELETE /v1/settings/external_calls_paused`.
The setting is applied after response delivery. A paused relay cannot carry
further settings calls, so use the local owner UI or an already configured
direct host to resume.

**Activity retention** in Agents controls independent limits of 0–100,000
audit and agent entries and 0–365 days. A zero means unlimited for that
limit. The host applies the saved limits during periodic Activity writes
and keeps diagnostics. Reducing a limit can permanently delete older entries.

Use `GET /v1/settings` to read `log_retention_entries` and
`log_retention_days`, `POST /v1/settings` to change either value, or
`DELETE /v1/settings/{field}` to reset it to unlimited. Authorized changes
apply after response delivery. Notes rate limits are independent of retained
Activity.

**Agent speech volume limit** in Agents applies to synthesized and played
agent audio inside Bridgit. It ranges from 10% to 100% and defaults to 70%.
System volume and owner-initiated playback are separate. Lowering the limit
interrupts an active synthesized utterance if it cannot be adjusted in place.

Authorized settings clients can read `speaker_volume_ceiling` through
`GET /v1/settings`, send an integer percentage through `POST /v1/settings`,
or restore 70% with `DELETE /v1/settings/speaker_volume_ceiling`.
Changes use the existing acknowledged settings flow. The speaker profile
reports the current limit as `volume_ceiling_percent`.

**Send crash reports** in Settings applies to Apple builds configured to
use Sentry. It defaults on. Turning it off stops capture and
cancels the upload session. Cached reports wait while reporting is off and
may send if you turn reporting on again. Pausing external calls blocks delivery
independently. This setting is separate from manually filed bug reports.

Authorized agents can read `crash_reports` in `GET /v1/settings`, set it
through `POST /v1/settings`, and restore the default with
`DELETE /v1/settings/crash_reports`. The setting is available only when the
build has crash reporting configured. For local packaging, iOS and iPadOS
use `BRIDGIT_SENTRY_DSN` or `iosSentryDsn`; macOS uses
`BRIDGIT_MACOS_SENTRY_DSN` or `macosSentryDsn`. The private local keys
belong in `keystore.properties`. A missing Mac destination leaves Mac
reporting unavailable.

Use **Device name** in device configuration to change the name Bridgit shows
and sends in new setup links. The operating system name stays intact.
Authorized agents read the override (`device_name`) and resolved name
(`device_name_effective`) with `GET /v1/settings`. Set the override with
`POST /v1/settings` using `{"field":"device_name","value":"Workshop"}`.
Use an empty string or `DELETE /v1/settings/device_name` to restore the
system-provided name. Writes use the acknowledged settings flow. Names allow
up to 60 UTF-16 units before trimming and exclude ASCII control characters.
Existing web account device records may retain their earlier reported names.

Maximum agent grants still depend on device settings and operating system
permissions for execution.

Direct discovery currently exposes the shared app routes for these grant
families.

- Device discovery, Activity, owner settings and agent management.
- Camera snapshots and microphone clips, subject to on-device controls and
  OS consent.
- Scoped Inbox interactions and events.
- Files, Shares and Downloads, subject to the device’s feature switches and
  per-agent ownership.
- Applet installation and management for the local core store, its granted
  records and media, and scoped reads, row changes, pause, and removal for
  installed editions in the maintained library.
- Called workflows and declared batches for the core store, with separate
  paid-service and speech permissions.

Connections use the same approved destinations and kept-photo scopes. Queued
direct sends retain their originating Tailscale device and recheck that
device before sending or retrying.

Direct agents can list owner-assigned satellites and forward only the target
routes advertised by those satellites. A missing or ungranted target
returns an error.

Maintained-library direct import, update, and called workflows remain open, as do account and linked-device administration
and inbound owner-configuration routes over direct HTTP. Relay and viewer link roles retain their own endpoints.
Real-device Tailscale reachability, network consent, and sensor hardware
require separate acceptance.

## Local host microphone clips

Authorized host agents can discover and call `POST /v1/mic/record` or the
`mic_record` MCP tool with a fresh idempotency key. The owner must allow host
microphone recording, and the caller needs the `mic.record` grant or host
administrator authority. The host needs microphone access and an awake execution
context. Requests supply whole `duration_seconds` from 1 to 120, within the
remaining transport deadline. The response returns M4A metadata and, by default,
`data_base64`. Optional `transcribe: true` uses only installed on-device speech
recognition, with an optional `language`; `return_audio: false` requires
transcription. Apple supports return-only storage with `store: []` or `"none"`.
Audio and transcript results expire immediately for retries, while the
idempotency marker prevents a second recording. Relay callers can submit long
microphone clip jobs with up to 150 seconds for recording, optional transcription
and delivery. Other operations retain the 120-second host deadline.

Agents with settings authority can read `host_microphone` with
`GET /v1/settings`, update it with `POST /v1/settings` using
`{"field":"host_microphone","value":true}`, or reset it to off with
`DELETE /v1/settings/host_microphone`. These changes use the existing
acknowledged settings queue. Poll the returned request URL for OS permission
or completion status. Settings report both current OS permission booleans in
`values.os_permissions` and authorization states in `values.os_authorization`.
The owner can grant speech recognition using **Allow on-device clip
transcription** on the host device, or change it in system privacy settings.
Microphone-only clips do not require speech recognition. MCP exposes the same
settings fields through
`settings_read`, `settings_change` and `settings_reset`.

### Capture notification consent

`GET /v1/settings` reports `os_permissions.capture_notifications`,
`os_authorization.capture_notifications`, and
`capture_notifications_remaining_action`. A settings.write agent may queue
`{"field":"capture_notifications_request","value":true}` with
`POST /v1/settings`; the request starts after the response is delivered and the
caller is reauthorized. Bridgit must be active for the OS prompt, and the owner
must grant permission. A denied setting reports `needs_os_permission`; an
inactive app reports a foreground-required failure. `DELETE` and `false` do not
revoke OS consent; the owner changes it in system settings. MCP exposes the same
settings tools. `/v1/capabilities` reports capture-alert availability in its
`notifications` boolean, separately from the unimplemented notification-reading
capability. Accepted scheduling does not prove an alert was displayed or seen.

## Publication hold — owner instruction, 5 Nov 2026

Keep this work and builds local until the owner tests M3 host/applet parity and
explicitly approves publication. No pushes, PR merges, uploads or public releases
for this work. After approval, iOS distribution is **TestFlight only**. Mac builds
remain local; Sparkle 2 is the chosen future updater, not permission to publish.

`publish-testflight.sh` now defaults to archive/export only. Its explicit
`--upload-approved` option is for use only after that approval. Signing expects the new Bridgit App Store profile and Bridgit testers group.
The clean app ID, APNs capability and profiles need owner-approved registration;
this script does not create Apple records automatically. Never run it merely to verify a local build;
it accesses signing credentials and account configuration. Use unsigned simulator
builds for ordinary checks.

### LAN history availability

`GET /v1/lan/discovered` and `POST /v1/lan/discover` include
`network.history_persistence`: `authorized_network` when OS-authorized identity
can select the same network history after restart, or `connection` when history
is limited to the current connection lifetime. This Apple availability field is
additive to the Android3347 response shape. It does not promise that any devices
were discovered, that observations are current, or that OS access will remain
granted. BSSID, its hash, and private history/connection keys are never returned.
Read `scan_performed` and each observation's state separately. Native Wi-Fi
entitlement/permission setup and physical-network verification remain unfinished
in this development build; unavailable identity truthfully returns `connection`.

### Wi-Fi history permission

`GET /v1/settings` exposes `wifi_history_authorization` (not_determined, denied, restricted, reduced_accuracy, allowed, unknown) and `wifi_history_remaining_action`. These describe location permission for Wi-Fi identity; they do not claim a Wi-Fi connection or readable BSSID. Actual LAN responses report `network.history_persistence`.

An authorized settings writer can POST `{"field":"wifi_history_permission_request","value":true}` to `/v1/settings` (or use `settings_change`). After acknowledgement and current-authority validation, the foreground app initiates the native prompt. Completion means the prompt was initiated or full permission was already allowed; poll the authorization fields for the OS outcome. Denied, restricted, or reduced-accuracy states report `needs_os_permission` with a system-settings action rather than a successful initiation. No API grants or revokes OS consent, false/reset is rejected, and denied/reduced access requires the remaining System Settings action. Hardware access provides the same initiation. Passive reads never prompt or request coordinates.
