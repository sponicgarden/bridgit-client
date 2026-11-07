# Applet events and webhooks

Applet event sources are part of the on-device applets/workflow runtime. They share its owner review, immutable workflow digest, agent grants, event stream, signed webhook outbox, retries and pause/revoke controls. Tapo is a source adapter, not a separate camera forwarding service. Nothing is enabled by upgrading.

An applet can accept JSON from a device/service through a webhook or subscribe to a selected Android app's notifications. Both become the same rich event and may run the applet's approved static actions. External devices must have a permitted route to the Bridgit device and authenticate as a paired agent. This does not create a public cloud ingress, bypass Tailscale, or make a camera with no webhook sender acquire one: Tapo uses its Android push notifications. Device-only events stay on the phone unless the owner explicitly enables an approved callback destination.

## Applet source contract

Propose a workflow with `workflow.propose` through the existing `/v2/call` or MCP tools. `workflow.validate` previews its derived grants. Set the trigger's type to `applet.event`:

```json
{
  "name": "Pet monitor person alerts",
  "trigger": {
    "type": "applet.event",
    "applet_id": "pet-monitor",
    "source_id": "tapo-push",
    "transport": "notification",
    "package": "com.tplink.iot",
    "adapter": "tapo",
    "event_type": "person.detected",
    "cooldown_seconds": 30
  },
  "steps": [{"type":"notice","title":"Camera person alert","text":"Check your camera."}]
}
```

The owner reviews and activates the exact source, transformation, filter and actions on the phone in Agent permissions. The applet and source IDs are stable labels within this agent's workflow, not credentials or authority over another app. Changing any trigger/action requires a new proposal and approval. Existing template revisions and desired instance configuration are not silently applied; the template registry's dynamic-execution support remains unchanged. An applet author can use this workflow runtime alongside its specialized device adapter without changing backend consent or accessing other instances.

All event sources need enabled/granted `workflows` and `events.read`. Notifications additionally need the private build, Android notification access, enabled/granted `notifications.read`, and that package selected for the same agent. Secret notifications, Bridgit's own notifications and group summaries are excluded. Notification content is processed only while unlocked, matching the existing notification-read policy. Locked-time pushes are skipped, not backfilled. Verify a real Android push: Tapo's internal message history alone does not prove that one was posted. No Android permission or app selection is silently granted.

Steps remain the existing bounded `notice`, `speak`, `ring`, `event` and `alert` actions and derive their own grants. Event text is untrusted input; it cannot select URLs, recipients, scripts or action arguments. `alert` refers only to owner-approved destination IDs. The first implementation supplies rich data to subscribed agents/services, while local actions use owner-reviewed fixed text. It does not silently add model calls, dynamic interpolation or new camera-control actions.

## Generic device webhook and transformations

For a webhook source use `transport:webhook`, omit `package`, and select `adapter:generic` (the default). An optional `mapping` maps normalized fields to dotted JSON object paths. No scripts, regex execution, network fetches or model calls run in a transform. Supported targets are `type`, `device_id`, `device_name`, `title`, `text`, and `occurred_at`:

```json
{
  "type":"applet.event",
  "applet_id":"greenhouse",
  "source_id":"door-sensor",
  "transport":"webhook",
  "adapter":"generic",
  "mapping":{"type":"event.kind","device_id":"device.serial","text":"event.description"},
  "event_type":"door.opened",
  "cooldown_seconds":30
}
```

POST to `/v2/applets/events`, with the existing agent `Authorization: Bearer ...` and a fresh `Idempotency-Key` for each source event. The same operation is available as `applet.event` through `/v2/call` and MCP. Body:

```json
{"workflow_id":"<approved workflow id>","event_id":"<stable sender event id>","payload":{"event":{"kind":"door.opened","description":"Greenhouse door opened"},"device":{"serial":"door-7"}}}
```

Intake returns the existing durable operation contract. Poll `operation.get` or `/v2/operations/{id}` until completion; HTTP 200/accepted is not proof that an action ran. Preserve both the Idempotency-Key and payload on transport retries. A source event ID is additionally deduplicated across distinct HTTP operation keys. Reusing one source ID with different input returns `event_id_conflict`. Only the workflow's owning agent can submit; webhook input cannot invoke notification-only sources. Relay agents use the existing authenticated tool route, not a new unauthenticated public endpoint. Never put credentials in URLs.

Normalized events have `schema:cc.bridgit.applet-event`, `version:1`, generated `id`, `applet_id`, `type`, Unix-millisecond `occurred_at` and `received_at`, `host_device_id`, `approval_generation`, `source:{id,transport,adapter,event_id,package}`, `device:{id,name}`, `title`, `text`, original `raw` JSON, and `content_is_untrusted:true`. The source-reported device identity is metadata, not independently verified identity. The authenticated agent/owner-approved source is the trust boundary.

Payloads are capped at 16 KiB. Mapping paths have at most eight object components; outputs are bounded. Absent `occurred_at` uses receipt time; supplied values must be positive Unix milliseconds and no more than five minutes ahead. Generic input defaults to `device.event`. The Tapo adapter recognizes English “Person was detected at …” and “Motion was detected at …”; other messages remain `notification.received`. No presence confidence, event image, recording URL or species inference is fabricated. Prefer an explicit mapped vendor device ID; Android notifications otherwise use a hash of the title so separate named cameras have separate cooldowns. Renaming a camera changes that fallback identity. Webhook events without a mapped `device_id` all use the source ID as device, so every sender on that source shares one cooldown per event type.

## Filtering, execution and delivery

A source filter matches the normalized event type exactly; omission accepts all. Cooldown is per workflow/device/type, defaults to 30 seconds and can be 0–86,400 seconds. Filtered/cooldown events return `accepted:false` with their reason and do not emit rich events or run actions. Duplicate receipts and cooldown timestamps survive process restart; each workflow retains the newest 256 receipt IDs and 256 device/type cooldown entries. Deduplication is bounded, so senders must not replay older IDs after eviction. At most 16 notification deliveries can be in progress or waiting on the phone. Excess pushes are skipped, not held in an unbounded memory queue. One metadata-only `applet.backpressure` event per matching workflow marks each overload burst, not each skipped push; it can fire again once a delivery finishes. Notification updates are keyed by Android notification key, post time and content; group summaries never produce a second camera event.

Accepted events are stored on the existing bounded agent event stream as `type:applet.event`, `resource_id:<workflow id>`, `data:<rich event>`. Their actions run serially through the existing workflow engine. Executions include `event_id` for correlation; this is Bridgit's generated rich-event `id`, not the sender's ID, which stays at `source.event_id`. Receipts are checkpointed before effects; interrupted/failed actions are not automatically replayed. After a crash, inspect execution state and the event stream before any fresh submission. This is conservative at-most-once dispatch within retained receipt history, not an exactly-once guarantee or an unlimited background queue. `reason:action_failed` reports an accepted source event whose action failed; the workflow's blocked state requires owner review.

Use `/v2/events` for cursor-based polling or Agent permissions → Approved recipients and event delivery to send this agent's events to an exact approved HTTPS destination. Existing outbox backpressure, gap markers, signatures, retry identity and delivery status apply. An accepted HTTP callback is service acceptance, not human acknowledgment. Receivers verify the existing signature/timestamp/nonce contract and deduplicate delivery IDs. Source pause, agent removal/revocation, package deselection or lost notification access suppresses rich content in subsequent event reads and cancels queued delivery. Reactivation starts a new approval generation, so pre-pause content cannot be delivered after resuming. Source action failure alone preserves the event for review while current read authority remains. Copies already delivered cannot be recalled.

No live source, subscription, outbound destination, email, production account reset or camera action is created by releasing this framework. Each real applet still needs its owner's source selection and intended response.

## Compatibility with earlier agents

`application.event` and `/v2/applications/events` remain supported aliases for intake. Both command names share the same durable idempotency reservation. New workflows should use `applet.event` and `applet_id`; `application_id` remains accepted, and supplying both IDs requires equal values. Existing approved workflow definitions are never rewritten: legacy triggers still emit `application.event`, `application.backpressure`, `cc.bridgit.application-event` and `application_id`. New triggers emit the corresponding `applet` names. Approval, grants, deduplication, pause and revocation checks are identical for both spellings. Existing error codes remain stable.
