# Modifying applets and templates: the control-agent guide

Follow the [agent workflow and photo-table example](https://bridgit.cc/learn/builder/practice-review.md#agent-workflow) to record both reviews and select applicable checks.

Before proposing an installed customization or publishing a template, run the [GLM practice review](https://bridgit.cc/learn/builder/practice-review.md) on the candidate and test the suggestions you adopt.


Status: API revision 121, 5 November 2026. Read the installed device’s `api_revision` first. Revision 101 adds durable local ancestry, guarded edits, template policy, conservative merge preparation and a typed context catalog. The control agent performs model-assisted resolution and behavioral verification; the phone never silently activates a model-produced upgrade. Older devices require the manual preservation workflow and do not enforce these new fields.

## Choose the kind of change first

- **Modify my applet:** change only the owner’s installed copy; preserve its origin and personal requirements.
- **Improve a template for others:** explicitly authorized publication, a new immutable template revision, migration notes and tests; never publish private instance settings or data. Copy built-ins before editing.
- **Take a template update:** compare old template, personal copy and incoming release; never overwrite the personal copy blindly.
- **Pause updates:** keep the relationship for later; suspend checks and upgrade work.
- **Detach and repurpose:** stop all template syncing; keep developing an independent personal applet.

Ask only if the user’s intended scope is genuinely ambiguous. A request to change their copy is not authorization to publish a shared template.

## Minimal Modify applet handoff

The owner taps **Modify applet** and chooses a configured control agent. The prompt names the applet, device and installed instance, gives the exact development read endpoint and links to these instructions. It contains no source, private settings or credentials. Bridgit shares that prompt with the selected app and copies it so the owner can paste it if the receiving app opens an empty chat. It queues `applet.modify_requested` with `instance_id`, `instructions` and the same `prompt` before opening the app. The selected agent gets scoped development-snapshot and update access; table-row, pause, removal and template-policy access do not transfer. The agent still needs Install applets permission, and proposed edits still require the owner's review. Fetch `GET /v1/applets/installed/{id}/development` through the existing connection, then ask what the owner wants changed. Never guess from a current public template.

## Default control-agent behavior

1. Discover the installed device's API revision and capabilities. On revision 101+, read `GET /v1/applets/installed/{id}/development`: exact package, configuration, revision, origin, baseline, customization journal and pending task. Retain an authorized copy. An owner control agent with `applet_install` can read any installed development snapshot. To propose a change, the agent must have requested this installation or be its assigned developer. For an owner-installed applet, the owner assigns the developer through Installed applets → its menu → Choose control agent; that explicit approval replaces its previous developer and does not grant unrelated capabilities. Never infer the current source from the latest public template. If the exact personal source cannot be recovered, stop replacement and explain that limitation.
2. Understand the desired outcome. Ask only for missing meaningful preferences, such as cooking goals, allergies or notification times. Never infer dietary restrictions from location or device context. Keep personal answers in configuration, not template source or published examples.
3. Prefer typed configuration (`config_schema`, `defaults`, `$input`), then existing flows, tables and native card pieces. Use sandboxed HTML for needs those pieces cannot meet. The owner can change all supported applet behavior; explain runtime limits rather than pretending packages execute arbitrary native code.
4. Before editing, record the user's requirement, including intentional removals, and an acceptance example. Revision 101 stores intent, acceptance checks, reported evidence and the previous definition/configuration in the device’s local customization journal. Retain external test artifacts in the owner-authorized workspace too; do not rely on conversation memory. Do not add invented metadata fields to the closed package schema.
5. Preserve stable package and component IDs, the exact upstream baseline, previous personal revisions and settings. Keep observations, photos, credentials and history outside the definition. Never publish a user's personal fork or send its contents to a hosted model without applicable consent.
6. Make the smallest change that delivers the outcome. Validate using `POST /v1/applets/validate`, inspect warnings, and test the original behavior plus the requested change with synthetic data or an approved isolated instance. Do not turn a live monitoring applet into a test fixture.
7. On revision 101+, send the complete personal package and a `change` object with `expected_revision`, `intent`, `acceptance` (1–20 checks) and `evidence` describing checks actually run. Include `task_id` when integrating a prepared upstream task. Stale revisions are rejected; read again and reconcile, never just replace the revision number. Agent edits wait for owner review, including when permissions are unchanged. Approval rechecks the revision. On older devices the update may apply immediately; serialize writes and never use update as a merge probe.
8. Read back the installed version and configuration, verify the real user-visible outcome, and report what changed, what was preserved and any history discontinuity. A changed watch may be replaced; unchanged watches preserve their IDs/counts. Do not promise universal history preservation or automatic rollback on today's runtime.

Steer by explaining the useful choice: “I'll make that a setting so you can change it again and keep future improvements.” For an incompatible upstream change: “The new planner conflicts with your weekly layout. I'll keep yours working; you can choose the new layout if you prefer.” Do not ask users to read code or resolve merge markers. Do not pressure them to accept a template's preferences.

## Onboarding and device context

Read `GET /v1/applets/context` (revision 101+) for typed metadata: field, operation, capability, purpose, freshness and fallback. This call collects no readings. Discover from the paired device, not a static list of everything a phone might expose:

| Need | Available discovery/read | Agent rule |
| --- | --- | --- |
| API/build compatibility | `GET /v1/health`: `api_revision`, `app_version` | Gate features by the actual installed contract. |
| Battery and power | `GET /v1/health`: `battery_level`, `charging`, `battery_runtime` | Respect unknown/learning states. Charging does not establish whether hardware has a battery. |
| Current location | `GET /v1/location`: coordinates, accuracy, fix time, age, precision | Use only when needed and granted; stale/denied is not a valid new fix. Offer manual city/region where adequate. |
| Other sensors, device identity, connectivity or actions | `GET /v1/capabilities`, then the advertised operation schema | Use only supported fields and operations; do not invent a universal variable name or infer readiness from a public example. |

Require `agent_allowed`, `enabled` and `ready` for the relevant capability. Read permission requirements and collect only what serves a named purpose. Request sensitive access in context, support refusal and revocation, and avoid repeated prompts. A location grant is not permission to retain or upload location. Keep device-only instances device-only; an upgrade is not consent to create a backend or send personal material to a remote model.

## Taking template improvements today

Keep the exact old template, current personal package and new template. Compare all three. Merge structured components by stable ID; compare text assets separately. Preserve user settings, removals and behavioral requirements. A conflict-free text merge still needs behavioral checks. If both sides change the same behavior incompatibly, keep the working version and ask one plain-language product question.

Validate the resulting complete package, use a new personal package version, and follow the update checks above. Never reinstall to get around an update error, reset invalid settings to template defaults, increase paid usage silently, enable sharing, or replace the personal package with an unmodified template. If a safe migration or reliable source snapshot is missing, defer that upgrade. The user can keep customizing their current version.

## Background preparation and the special upgrade task

On revision 101+, opening an applet schedules a background source check (at most once per six hours per instance, including failed checks). Startup also checks tracked copies; bundled templates are considered after startup. Launch never waits. Registry templates resolve the latest accessible **published** revision; URL templates read their original URL. Detached/paused copies do not start checks.

The phone retains an `applet.template_upgrade` task locally and emits an event of that name to the assigned developer. Read the development snapshot for the task: `needs_agent` means conflicts or validation failures; `needs_verification` means the conservative merge passed structural checks but behavior still needs verification. These states are not permission to apply it. Billing remains `null`; no hosted model is invoked by the phone.

To request preparation yourself, POST the same origin (`url` or `template` + `revision`) and `expected_revision` to `/v1/applets/installed/{id}/template-upgrade`. An inline package cannot masquerade as a template release. Preparation changes the instance revision, so read a fresh development snapshot afterward. The task includes old ancestry, incoming release, candidate, conflict paths and structural validation through the snapshot/task fields.

As control agent, route this as the special task type, resolve against the durable user requirements, run behavioral checks, then submit the resulting personal package through `/update` with the fresh revision and task ID in `change`. Describe any conflict in plain words and ask only when the user must choose behavior. Never claim tests ran merely because the candidate parses. The owner sees your intent and reported verification before approving. Approval integrates the incoming baseline while retaining personal differences. A cancelled/stale task cannot be applied as that task.

Device-only remains local. Remote model processing requires applicable owner consent; a developer/Bridgit paying for a task does not grant permission to upload personal source or settings. If no authorized model/agent is available, retain the working applet and the pending task. Do not imply the phone can execute a model job by itself.

Automatic model execution, transactional resource activation/data rollback and reattachment tooling remain future work. Current runtime updates can replace watches and may fail part-way; the new service deliberately prepares instead of silently activating. The local customization journal is bounded at 100 entries and never silently evicts user requirements; export the development snapshot before reaching this limit and do not bypass the limit by resetting the applet.

## Detach when the purpose changes

If the owner wants to repurpose Chef’s Helper into a laboratory inventory tool, recommend detaching: “This becomes your own applet and stops taking updates from Chef’s Helper.” Detaching preserves the running applet, configuration, data, permissions and origin history. Stop template checks, queued upgrades and update prompts; do not remove/reinstall or silently detach to avoid a difficult merge. Bridgit itself can still receive updates.

Revision 101 enforces these states on the device. POST `/v1/applets/installed/{id}/template-policy` with `state`, `expected_revision` and the owner’s `reason`. The owner can also use Pause/Resume template updates or Detach from template in the applet’s menu. This cancels pending updates and invalidates stale approvals. Personal edits remain available while paused or detached. On older devices, record the choice and stop your own checks, but do not claim other agents or the runtime enforce it.

The current API deliberately refuses reattachment from detached state; resuming applies only to paused tracking. A future reattachment workflow requires an explicit request, selection of a template and compatibility review. Recover the real baseline and test the personal requirements. If ancestry is missing, treat this as a new integration. Never resume syncing solely because an origin link remains.

## Publishing template improvements

Use the existing registry's draft/revision/publish lifecycle only when the owner asked to publish. Never overwrite an immutable release. Preserve component IDs, declare compatibility and migration changes, and test representative personal customizations, intentional removals and invalid settings. Scrub private configuration, observations and secrets before publication. Publish a sanitized repurposed copy as a new template identity only when explicitly requested; detachment alone does not publish anything.

## Approve the exact pending change

A controlling agent with the existing `owner.configuration` and applicable action
grants can complete app-owned approval without another phone tap (revision 121+).
List `GET /v1/owner/configuration/approvals`, inspect
`GET /v1/owner/configuration/approvals/applet/{pending_id}`, then POST to that same
URL with `{"approve":true,"expected_digest":"<digest returned by review>"}` and an
Idempotency-Key. Use `approve:false` to decline. MCP exposes the same operations
through `configuration.get` and `configuration.set`. Re-read after a conflict; the
digest binds config, deletion acknowledgment, source and expected instance revision.
Read back the installed version and saved data after approval. Android consent,
account verification and initial control-agent grants retain their real steps.

## Required practice contract

Before creating or changing an applet, read [the practice contract](https://bridgit.cc/learn/builder/applet-practices.md) and [catalog](https://bridgit.cc/learn/builder/practices.json). Record the catalog review in `ui.practices`. The validator reports applicable practices, defaults and checks. Run the named behavioral checks; unverified is not passed. Use a justified exception only for its exact target. Reassess exceptions when the target changes.
