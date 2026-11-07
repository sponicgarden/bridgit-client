# Build an applet with this device

Read this brief first. An applet combines the device's senses, decisions and actions into a useful result. Installed packages run on the device when your chat is offline. Most need no APK or native code.

## Connect to the right device

Use the owner's existing authenticated Bridgit connector. Read `GET /v2/capabilities` and compare `device_id` with the target device ID in the handoff. A connection to another phone is not enough. If this device is absent or unreachable, stop and ask the owner to return to **Bridgit → Add control agent** to finish provisioning. Do not invent a connection or ask them to paste a key into a public page.

Read only the relevant parts of the installed guide. `GET /v1/capabilities` says what this key can use (`agent_allowed`, `enabled`, `ready`, `blocked_reason`). `GET /v1/services` lists available model and external services; `GET /v1/limits` gives current budgets. Discovery does not capture media, run a model or grant access.

## Start with the person's goal

Ask: **What would you like this applet to do for you?** If they already described it, use that description. Agree on the result, when it runs and how they see or hear it. Ask only for missing decisions.

If they want inspiration, offer three specific ideas grounded in their interests and the connected device. Explain the useful outcome and the combination that makes it work. Do not start with a generic list of scanners, notebooks and reminders.

Examples of combinations, subject to discovery:

| Useful outcome | Parts to combine |
| --- | --- |
| Cook from what is already in the kitchen, with occasional spoken help | Ingredient photos + chosen cooking goals + workspace observations + speech |
| Turn a short scene and portrait into a narrated fictional adventure | Reviewed video/photos + location context + story generation + speech |
| Make a searchable home inventory from room photos | Owner-reviewed photos + recognition + durable tables + photo-linked records |
| Notice a meaningful change and keep evidence, rather than stream everything | Camera watch + change filter + bounded model judgment + saved observation + notice |

These are starting points, not a claim that a service or template is ready on this device. Prefer a different idea when it better serves the owner.

## Find a template, then customize

Query `GET /v1/applets/catalog?q=<owner's goal>`. Read names, summaries and pinned revisions; follow `next` using `after` until enough relevant choices are found. This live, device-authorized catalog is the current library, not a fixed list copied into this page. The [recipe index](https://bridgit.cc/learn/builder/recipes.json) supplies additional worked compositions. `GET /v1/discover?q=<goal>` maps the idea to first calls and blockers.

Prefer a suitable template and its configuration. Inspect its definition before promising behavior. No close fit? Compose a package using the [package reference](https://bridgit.cc/learn/builder/applet-packages.md):

- **Sense:** explicit photos/video, microphone, location, sensors or authorized device context.
- **Decide:** local change detection, a bounded model judgment, conditions or the owner's answer.
- **Act:** a notice, speech, alert, scheduled action or an authorized integration.
- **Remember and show:** typed tables, photo references, processing status and native card pieces. Keep durable data on the device, not only in HTML or chat memory.

Check the discovered grants and service costs before choosing parts. Keep capture review, progress, cancellation, pause and deletion understandable. Follow the [interaction standard](https://bridgit.cc/learn/builder/applet-interaction-standard.md). Modifying something already installed? Read its development snapshot and the [preservation guide](https://bridgit.cc/learn/builder/modifying-applets.md) first.

## Validate, install and prove it works

Before authoring, open the [practice workflow and worked example](https://bridgit.cc/learn/builder/practice-review.md#agent-workflow). Plan from `by_task.all` plus relevant task tags in the [catalogue](https://bridgit.cc/learn/builder/practices.json). Once a candidate exists, use validation's applicable practices and named checks as the worklist.

Record the reusable-lesson decision in package `ui.practices.review`. Run the GLM candidate review and keep its digest and disposition in request `practice_review` when the operation requires it. Re-review after candidate changes. Run the [GLM practice review](https://bridgit.cc/learn/builder/practice-review.md) and evaluate its suggestions. Call `POST /v1/applets/validate` with the pinned template/revision or authored package and config. Resolve actual blockers; use the owner's existing configuration authority where granted. Keep real OS consent and initial authority checks.

Call `POST /v1/applets/install` with the same reviewed payload and an `Idempotency-Key`. Await the required approval and `applet.installed` event. Confirm the installed entry and test one agreed example. An accepted request or delivered notification alone is not a working applet. Report the observed result, any untested behavior, and how to pause or remove it.
