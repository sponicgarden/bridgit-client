# Authoring workflow steps

Select a trigger from `workflow-trigger.schema.json`. It describes the filled trigger object, including the unresolved package watch binding `{"$watch":"main"}`. A card can start a button or called flow on the Apple host. Power, battery, sun, charging, intervals, geofences, watch matches, mode transitions and incoming application events are dispatched by an authorized Android host. A package may contain named flows with different triggers.

The workflow envelope requires `name`, `trigger` and `steps`. Optional fields are `ui`, `cooldown_s` (0–86,400), `max_runs_per_day` (1–1,000), `max_paid_per_day` for paid workflows, and a flow-level `only_if`. Put a flow ID alongside the envelope in a named `processing.flows` entry. The compiled workflow limit is 64 KiB. A flow-level condition can read the trigger before the first step. A step-level condition may also read earlier results. Event-source cooldown defaults to 30 seconds per device and event type. When both cooldown spellings are present they must agree.

A flow runs its `steps` in order. Use `{"$step":"shot.photo"}` for an earlier
result and `{"$trigger":"with.row"}` for a declared called-flow argument.
Give any step referenced later a unique `id` matching `^[a-z][a-z0-9_]{0,31}$`.
Text fields accept a string, one reference or a list of 1–20 literal and
reference parts. Use whole JSON values when a field expects a boolean, number,
object or array. The service catalog describes model selection and owner setup.
The following return values describe the Android host. Check the target host
before offering a flow on another platform.

A condition names one source. For example, `{"step":"look.answers.present","is":true}` reads an earlier result and `{"trigger":"with.room","is":"kitchen"}` reads an argument declared by a called flow. Conditions use plain paths. In a step input, wrap the path with `$step` or `$trigger`.

Use `{"time":{"between":["22:00","07:00"],"days":["fri"]}}` for Friday night through Saturday morning. Combine two to eight individual tests with `all` or `any` at one level. For example, `{"all":[{"phone":"charging","is":true},{"count":{"table":"visits","since":"today"},"below":3}]}` checks charging and the applet's own rows. The `condition` definition in `workflow-step.schema.json` lists each source and its allowed comparisons. Run the offline validator to check referenced step IDs, declared arguments and required scopes.

Every step accepts `only_if`. The default error policy stops the run.
`on_error:"skip"` continues after a failed step. `on_error:"retry"` accepts
`retries` from 1 to 3, default 1, with delays of 2, 5 and 10 seconds. Cloud
speech steps forbid implicit retries. Design downstream references for
skipped steps, refusals and timeouts. A workflow contains 1–32 steps, up to
four paid steps per run. A paid workflow's daily call ceiling defaults to 50
and can be set to 1–500 with `max_paid_per_day`. Set a separate monetary budget.

| Step | Inputs, behavior and useful output |
| --- | --- |
| `photo` | Optional `camera` defaults to rear. Use front, rear or a configured local camera ID beginning with `lan:`. Optional `view` names an instance-local saved PTZ position on a LAN camera. Use a lower-case letter followed by at most 31 lower-case letters, digits or underscores. See the package contract for setup and renewal after re-enrollment. A built-in lens uses its current position. The returned `photo` refers to temporary image data in this run. Set `keep:true` to also return a durable `kept` reference. The result includes `camera` and `taken_at`. |
| `ask_model` | Set `model` to jev, clef or glm. Supply 1–10 named `questions` with a type and instructions of 1–1,000 characters. Supply criteria for choice answers. An optional `photo` must reference an earlier photo step's `.photo`. Read `answers.<question-id>`. A call consumes paid allowance. |
| `ask_owner` | Supply a `title` of up to 120 characters, optional `text` up to 4,096, and 2–6 distinct `choices` of 1–80 characters. `wait_s` defaults to 300 and accepts 30–3,600. The result has `interaction_id` and `timed_out`. Read `choice` only when answered. |
| `wait` | Supply integer `seconds` from 1 to 300. Execution resumes after the delay with an empty result. |
| `stop` | Ends this run when reached. Use `only_if` to stop on a particular answer or condition. |
| `notice` | Display a `title` up to 120 characters and optional `text` up to 4,096. Optional `priority` is normal or urgent. Returns `interaction_id`. |
| `speak` | Supply `text` up to 600 characters. `allow_cloud` defaults to false. Speech uses the selected model subject to owner permission. Returns `play_id`, queue `position`, `status` and speech metadata. |
| `hear` | Listen for `duration_seconds` from 1 to 30 (default 5). Optional `language` is 2–32 characters. `allow_cloud` defaults to false. Read `transcript` and processing/model metadata. |
| `ring` | Rings the device for `seconds` from 1 to 30, default 10. Returns an empty result. |
| `alert` | Supply `text` up to 1,024 characters and 1–3 owner-approved destination IDs in `destinations`. Returns `alert_id`. Resolve real destination IDs before installation. |
| `event` | Supply `text` up to 120 characters. Emits the device's workflow signal associated with this workflow. Returns an empty result. |
| `record` | Name a declared `table` and a `row` containing 1–16 declared columns. Read `row_id` for a single write. With `for_each` referencing an earlier list, use `{"$item":"field"}` in the row to write at most 50 entries. Read `count` and `row_ids` for a batch. |
| `appraise` | Supply a kept `photo` or a reference. Optional `max_items` accepts 1–30, default 20. The paid inventory service returns `items`, `count`, `model`, `cost_usd` and sometimes `room`. |
| `service` | Select a literal service ID and operation. Use `chef.signed-edition-budget.v2` for `scan` or `observe`, or `appraise.signed-edition-budget.v2` for `appraise`. Scan takes a photo reference and optional area. Observe captures a frame and accepts session and tip timing. Appraise takes a kept photo or reference and optional maximum item count. The results match the corresponding kitchen or appraisal operation below. Include a specific transfer disclosure in `permissions.remote`, grant `workflows` and `decisions`, and set `data_policy.device_only.remote_models` to true. Observe also requires `camera.snapshot` and `audio.speak`. A signed host call requires a published edition installed by a linked owner. Use stop or skip for errors. |
| `kitchen` | Set `mode` to scan or observe. Scan needs a photo reference. Optional `area` is Fridge, Cupboard or Spices. Scan sends the photo and optional area and returns ingredient items. A scan-only package can use its own table names. Observe reads the applet's cooking-session settings, collects a camera frame and can speak a tip. Both modes need the enabled instance portal and a paid model grant. Use the chef-helper example for the observation tables and session controls. In observe mode, `session_s` limits the session to 10–3600 seconds (default 3600). `tip_gap_s` sets the minimum time between suggestions to 10–3600 seconds (default 45). The interval trigger separately sets the sampling period. |
| `update` | Name a declared `table`, a stored `row` ID or reference and a `set` object of 1–16 declared columns. Returns `row_id`. |
| `share` | A button-triggered flow can export a declared `table` `as` csv or pdf. Optional fields select `columns`, `group_by`, numeric `totals`, `photos` (thumbs or none), a row filter `where` and a `title`. The owner chooses a destination in the share sheet. The result describes the created file, format, rows and pages. |
| `send` | Set `to` to an approved connection ID. Optional `data` holds up to 32 fields and 16 KiB, `text` up to 1,000 characters and `photo` a kept image or reference. By default the call returns `queued` and `delivery_id`. With `wait_reply:true` the host waits up to 15 seconds for a bounded reply. Connection credentials live on the host. |
| `context` | Reads the device's Automated Context status under the owner's context grant. Takes no step-specific fields. Inspect the device context schema for the result fields you use. |
| `set_mode` | Set `mode` to a mode configured on the host, such as away. Returns `mode`, `from` and `changed`. |
| `lookup` | Name a declared `table`. Optional `where`, `since` and `hours` filter rows. `limit` accepts 1–10 and defaults to 1. Returns newest matching `rows`, total matching `count`, and `row` (the first match or null). |

Read `flow-contract.json` for each step's base scope. Conditional scopes depend
on settings, trigger and service selection. Run the validator to derive them for
your package. Approving a workflow, granting a capability, enabling a portal and
configuring a paid service are separate owner actions.

### Local execution on iOS and macOS

The current Apple host starts installed button flows in the foreground.
Supported steps include `hear` (on-device, foreground button flows), `ask_owner`, `notice` (normal priority), `wait`, `stop`,
`record` (single row or atomic `for_each` batch), instance-scoped `update`, reviewed `photo` (front or rear), `ask_model` with
the signed Clef service, `appraise`, and `kitchen` with `mode:"scan"`.
The last two accept an instance-owned kept photo. Returned items can feed
`record.for_each`; see the package examples for Chef’s Helper and Insurance
Inventory. For Clef, declare a nonempty remote disclosure, opt
into remote models, set monetary and call limits, and expect the owner to
approve each submission. Installed definitions and records persist offline.
Capture requires an available camera. Clef also requires connectivity and a
linked owner budget. Other steps, automatic watch capture and schedule triggers
require additional Apple host support. A package can validate or appear in
the library before its triggers and steps can execute on that platform.

A card can invoke a called flow over saved table rows on Apple. In the two
vision examples, `for_each` selects a table source, `only_if` compares a
column with `is_not`, and `with` binds named text arguments from row columns.
`max` bounds the number of matching rows processed per tap. The owner reviews
one hosted submission per photo. A completed row is excluded by its updated
status. Reopening an interrupted batch reuses its locally saved provider answer
and row IDs. If the app closed before it received and saved the provider answer,
another attempt requires fresh approval and can incur another charge.

### Choosing a host for card actions

| Package action | Android | iOS and macOS |
| --- | --- | --- |
| Voice Notebook card, `POST /v1/mic/record` | This card requires Android 13 or later and an installed offline speech model. With no local recognizer it returns `not_supported` before recording. A recognition error with `return_audio: false` fails the call. | Use `transcribe: true`, `return_audio: false` and 1–30 seconds. The host requires on-device speech recognition in the current language. Review the transcript in a draft before confirming the save. |
| Voice flow, `hear` | Set `allow_cloud: false` for the owner's on-device binding | In an owner-started flow, declare `microphone.clip` and `workflows`. Set `allow_cloud: false` or omit it. The host uses Apple's on-device recognizer with the requested locale or the current locale. A denied permission, missing recognizer or unavailable on-device language fails the step. Result fields include `transcript`, `processing: "device"`, `model: "apple-on-device"` and `cost_usd: 0`. |
| Receipt Scanner card, `POST /v1/ocr` | Declare both `ocr` and `gallery`. Android reads the selected, authorized gallery image for review. | Use the same portable manifest and `{"gallery_id":{"$input":"gallery_id"}}` with an integer input. The owner selects an image; Apple Vision returns `text` on device. The selected image gets an installation-local numeric ID. Review the draft before saving. |
| Scheduled photo watch | An authorized monitoring phone runs the installed watch | Use the edition's Android install link for unattended capture. The local card runs supported foreground actions. |
| Legacy Dog Monitor, `android.dog.v1` | Runs the specialized tracker | Review the pinned classifier, export the definition, share the install link or view an existing account monitor. |

For a button-triggered main flow, `{"run_workflow":true}` runs `processing.workflow` or the first entry in `processing.flows`, in declaration order. For a named called flow, use `{"flow":{"$flow":"id"}}`.

Test the actual card action or trigger on the intended host before publishing a platform claim.

For a device dashboard, declare required permission `lan_control` and a card source named, for example, `lan` with `{"call":{"method":"GET","path":"/v1/lan/devices"}}`. Bind the devices piece’s `items` to `{"$source":"lan.devices"}` and use the field names `name`, `adapter`, `availability` and `verified_at`. Android reads this applet agent’s own enrollments. On iOS and macOS the owner chooses a paired Android phone with the same registry key and revision installed and approved. The phone supplies its applet’s enrollment names and verification times. Listing does not contact the devices. Treat a relay or permission error as an unavailable source and a successful empty result as zero enrollments.

When a package declares `processing.watches`, its Apple detail screen can show the watch activity on a paired Android phone. Share the exact registry install link, approve the edition and permissions on that phone, then select it under Monitoring phone. Refresh reports per-watch state, counts and the most recent check time and outcome. The monitoring phone executes the watch independently of the Apple viewer. Automatic capture still requires its granted permissions, enabled data sources and available hardware.

For companion watch cards, bind a named source to `{"watch":{"$watch":"main"},"read":"status"}` or `"read":"tally"`. Use the package watch key in place of `main`. A status source supports `checks`, `matches` and a `recent_checks` timeline (last ten checks). A tally source supports `today.checks`, `today.watched_minutes`, a `today.labels` table keyed by activity label and an `episodes` timeline with `start`, `label` and `minutes`. The monitoring phone supplies up to 20 recent episodes from the last day. A failed or skipped check reports its outcome separately from a successful non-match. When the paired phone lacks the requested source, the Apple host shows it as unavailable.


## Counting observed episodes

In a watch request, `tally.gap_s` sets the gap for joining sightings into one episode and for joining observed coverage spans. Set it to at least the configured sampling interval. A package may override the episode gap for individual tracked labels. For example, `{"track":"activity","labels":["resting","eating","drinking"],"gap_s":60,"label_gap_s":{"resting":120}}` joins resting sightings up to two minutes apart and meal or drink sightings up to one minute apart. Coverage still breaks after one minute. Each override takes a whole number of seconds from 1 to 21,600. When the interval exceeds a label's episode gap, consecutive scheduled samples can count as separate episodes. The Android watch host computes these totals, including when its paired Apple client displays them.

Set `start_paused: true` in a watch request to wait for an explicit resume before taking the first reading or firing rules. This applies at creation. Subsequent launches restore the saved pause state. A paired Android host honors this field for camera and sound watches.

Use `tally.typical_gap_basis: "continuous_seen"` when an interval between two episodes is meaningful only if the subject stayed in view. The median uses consecutive episode starts within a single observed span. A failed reading, a failed visibility test, or silence longer than `gap_s` ends the span. The result is omitted until there is one covered pair. The default `"elapsed"` uses wall-clock gaps once three episodes have occurred.
