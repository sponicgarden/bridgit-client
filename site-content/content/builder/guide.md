# Bridgit Builder recipes

**Agents: read https://bridgit.cc/learn/start.md first.** It picks the recipe for the owner's idea, lists the first calls and says what done looks like; each recipe's `start` block carries the same plan, and `GET /v1/discover?q=<idea>` returns it with this phone's blockers. This page is the reference behind it.

The canonical operation schemas are [OpenAPI](https://bridgit.cc/learn/openapi.json), the [endpoint catalog](https://bridgit.cc/learn/endpoints.json), the [guide](https://bridgit.cc/learn/guide.md), the [v2 tool guide](https://bridgit.cc/learn/control.md) and [changes](https://bridgit.cc/learn/changes.json).

[Recipe index](https://bridgit.cc/learn/builder/recipes.json) · [Human examples](https://bridgit.cc/recipes/) · [Decision contracts and current costs](https://bridgit.cc/learn/decisions.md)

## Preserve the owner’s applet by default

Before installing, customizing or updating, follow [Modifying applets and templates](https://bridgit.cc/learn/builder/modifying-applets.md). Prefer configuration and native components; retain the exact template baseline and personal changes; discover device context with least privilege; validate and verify before replacing a working revision. Current package updates do not automatically merge template changes.

## Install as one applet (the default)

Every recipe is an installable applet package: `https://bridgit.cc/recipes/<id>/applet.json`. Call `POST /v1/applets/validate` with `{"url": "…/applet.json", "config": {…}}` to see the owner's review, any `warnings` and the complete file, then `POST /v1/applets/install` with the same body. The owner approves once on the phone, and the phone creates the watches, flows, tables and card itself and keeps them running with no agent. You don't keep a `watch_id` or fill a card by hand. The card is made of pieces the phone draws, so you write no HTML. To change a recipe, edit its `applet.json` and send `{"package": …}`. Every field, with a worked flow example: [applet packages](https://bridgit.cc/learn/builder/applet-packages.md).

The rest of this page is the reference for building the same parts one by one with your own controller. Do that only when you can't get the Install applets permission, or need something a package can't do.

## Choose a composition

| Stable recipe ID | Result | Runs on phone | Controller work |
|---|---|---|---|
| [pet-monitor](https://bridgit.cc/recipes/pet-monitor/) | Photo-based activity tally | Camera timer, local filter, selected remote model, tally, pause, offline card actions | Create/configure, retain watch ID, archive history, interpret gaps |
| [receipt-scanner](https://bridgit.cc/recipes/receipt-scanner/) | Owner-confirmed OCR draft | Selected-image OCR, card editing/answer | Validate totals, deduplicate, store approved receipt |
| [voice-notebook](https://bridgit.cc/recipes/voice-notebook/) | One bounded transcript | Explicit 10-second capture, offline speech, editable card | Save confirmed text, optional summarizer |
| [room-change-watcher](https://bridgit.cc/recipes/room-change-watcher/) | Image-change events | Timer, camera baseline/comparison, event, pause | Interpret selected events, retain event history |
| [daily-briefing-panel](https://bridgit.cc/recipes/daily-briefing-panel/) | Daily reminder and weather panel | Approved reminder, explicit weather refresh | Merge manifests, publish card; multi-source briefing is an extension |
| [external-device-dashboard](https://bridgit.cc/recipes/external-device-dashboard/) | Enrolled-device inventory | Authorized inventory read, optional authorized status read | Configure enrollment separately; retain observation timestamps |
| [situation-counter](https://bridgit.cc/recipes/situation-counter/) | Episode count of one picture situation (library id or own words) | Camera timer, change check, Clef yes/no, tally, alert notice for urgent situations, offline count card | Pick the situation with the owner, retain watch ID, archive day totals |

These examples do not automatically install an applet, enable switches, enroll devices, move a camera, or capture anything on page load. Local cards do not become background controllers. The seven JSON recipes list their `capability_ids`, machine-readable `operations`, `execution`, `lifecycle`, cost/failure behavior and source `evidence`. Canonical IDs are `v1.<capability>`; v2 tools have distinct `v2.<capability>` authorization and are not interchangeable.

## If something isn't ready

One rule: call `GET /v1/capabilities`; use a capability when `agent_allowed`, `enabled` and `ready` are all true, otherwise tell the owner the named blocker once. The recipes need API revision 54 or newer (situation-counter: 71): if `GET /v1/health` shows a lower `api_revision`, the phone needs a Bridgit update (see [changes](https://bridgit.cc/learn/changes.json)). Public pages can't see the owner's phone, so their `ready` is `null`; the phone's own answers decide.

Read-only discovery never grants permission to run a recipe. Send the owner's real key only to the authorized device/relay; do not embed it in HTML. Controller HTTP requests use the paired base URL and Authorization header. A card calls `Bridgit.act` as its posting agent, with the same grants and switches; the card holds no key. A team decision key belongs to the optional server API and is a different credential.

## Create and render a card by hand (fallback)

Every `/recipes/<id>/request.json` is a **complete body** for `POST /v1/interactions`, including the full offline HTML. It is not an HTML URL for the phone to fetch. To try its buttons, open it in the [card simulator](https://bridgit.cc/simulator/) (below). The installable `/applet.json` uses a card made of pieces instead, which the phone draws itself; the recipe page shows a preview of it. `/recipe.json` includes complete synthetic request/response examples and the same card body. Fictional IDs in fixtures must be replaced with returned IDs.

For watches, the controller first sends the `start` example, stores the returned `watch_id`, and fills the card's `RECIPE.resource_id`. For receipt OCR, fill `RECIPE.gallery_id` with the authorized selected image ID. To fill it, JSON-encode the values and escape `<` so no value can close the script tag; do not concatenate untrusted source strings into HTML. Never put keys in that configuration.

Post the complete interaction body, retain `interaction_id`, inspect `display` (a delivered notification is not proof of an open card), then wait with `GET /v1/interactions/{id}?wait=55` or one `/v1/events` loop. An HTML answer is in `answer.data`. A successful bridge action resolves to `{ok:true,status:200,body:...}`; errors use `{ok:false,status,error,body}`. Inspect the body and expected ID/state before changing the UI to stopped or paused. A status 200 alone is not proof of the desired resource state.

```javascript
// Inside a card, with a real controller-supplied watch ID:
const bridge = window.Bridgit || window.Bridget;
const result = await bridge.act({
  method: 'POST', path: '/v1/watches/' + watchId + '/pause',
  body: {paused: true}
});
if (!result.ok || result.body.user_paused !== true) {
  // Show an error; do not claim the watch paused.
}
// A response is accepted once per question view. This requests controller work.
bridge.respond(JSON.stringify({recipe_id:'pet-monitor',action:'archive'}));
document.addEventListener('bridgit:answered', () => {
  // Bridgit persisted the answer; controller processing remains unconfirmed.
});
```

`Bridgit.respond(JSON.stringify(value))` is compatible with the old `window.Bridget` alias (the only name on 1.6.269 and older). The templates require a build with `Bridgit.act` for live actions and conservatively gate the recipe bundle at revision 54; absence of the bridge is an explicit error, never a simulated success. HTML is limited to 256 KiB, answers to 16 KiB, no network/frames/external images/scripts/fonts. Only the native bridge reaches the posting agent's endpoints. Each successful or refused action is visible through `action.executed`; optional server calls initiated by an endpoint still require internet even while the controller is offline.

## Card flows: keep the owner in the card

Cards open full screen by default. When the owner answers or closes one, the card shows a panel ("Answer sent" or "Closed"), then the next waiting card in its stack comes up. With nothing left, Bridgit steps back to whatever the owner was doing. That is right for a single card, but a flow (question, then a result or the next question) would drop the owner out between cards.

- **Send `follow_up: true` on every card that has a next one.** After the answer or close, the card says "Waiting for the next card…" and stays up to 30 s. Your next card replaces it in place, and Bridgit steps back only after the last card. Leave `follow_up` out on the last card.
- **Post the next card as soon as the event arrives.** `interaction.answered` and `interaction.dismissed` for a `follow_up` card carry `follow_up_seconds` (30): that is how long the screen waits (if the owner answered from the notification instead, nothing is held and your next card simply opens). Keep a `GET /v1/events?types=interaction.answered,interaction.dismissed&wait=55` loop open during the flow, not one wait per card started later.
- **Plan for a close.** Dismiss is an answer too: the owner said no. If the flow continues, send the closing card (for example "OK, stopped"), or nothing; never re-post the same question.
- **Known steps up front: use a stack.** Cards that don't depend on each other's answers go in one `stack`; the owner swipes through them and the next waiting one comes up after each answer. Use `follow_up` when the next card depends on the answer.
- **Finish with a result card,** an html card or notice with `follow_up` left out, so the owner sees what happened before Bridgit steps aside.
- `no_takeover: true` cards never take or hold the screen; `follow_up` is ignored on them.

```javascript
// Agent side: a two-step flow.
const q = await post('/v1/interactions', {kind: 'html', title: 'Pause the pet monitor?', html, follow_up: true});
const ev = await nextEvent(['interaction.answered', 'interaction.dismissed'], q.interaction_id); // within follow_up_seconds
await post('/v1/interactions', {kind: 'html', title: 'Done', html: resultCard(ev)}); // last card: no follow_up
```

## Test a card without a phone

The card simulator runs a card on a computer with the phone's own card rules and a simulated phone behind `Bridgit.act` and `Bridgit.respond`. It is a simulator: replies are mock data and a desktop browser is not the phone's WebView, so check the card on a phone before relying on it.

- **In a browser:** https://bridgit.cc/simulator/ (add `?card=recipe-pet-monitor` for a recipe). Pick a recipe, example or library card, or paste your own HTML. It shows the card about 400px wide, logs every call with the mock reply, warns about anything the phone refuses or blocks (over 256 KiB, frames, outside files, `fetch`, storage, dialogs, no bridge check), and can make the phone fail on purpose: agent not granted, feature switched off, Android permission missing, Bridgit off, no internet, sensor busy, over a limit, a Bridgit bug, card not attached.
- **Headless, for agents:** download https://bridgit.cc/simulator/run-card.mjs and run it with Node 22 and Chrome or Edge installed (no packages):

```
node run-card.mjs card.html --click "text=Pause" --json
node run-card.mjs card.html --failure capability_disabled --click "#refresh"
```

It prints each step, every `act` call (method, path, body, status, reply), every `respond` answer, script errors, blocked loads, the rule warnings and the card's visible text; it exits 1 when it finds a problem. Steps: `--click` (CSS selector or `text=Label`), `--type "#field=words"`, `--wait <ms>`, `--eval "<js>"`. `--help` lists the failure modes. In Node, `import { runCard } from './run-card.mjs'` returns the same result as an object.

What it copies from the phone: the page wrapper, CSP and the phone's own `Bridgit`/`Bridget` bridge script; one accepted answer per view (16 KiB, strict JSON); the `bridgit:answered` event and "Answer sent" panel; `act` results as `{ok,status,body,error}` with the phone's error codes; 8 actions in flight; unknown endpoints as `not_found`. Recipe cards in the simulator are set up with its mock watch id (`w0123456789ab`, `wroomchange01`) or gallery id (`42`). Endpoints it has no mock for answer `200` with a note.

## Show a card in Claude, ChatGPT and other chat apps

The same card HTML also runs inside chat apps that support MCP Apps; you change nothing in it. Bridgit's MCP server (`/v1/mcp`) gives the chat app a small viewer that supplies `window.Bridgit` (and `window.Bridget`). Call `card_show` with `html`, `sample_id` or `interaction_id` to show a card in the chat only, or `interactions_send` to post it to the phone and see it in the chat too.

- In the chat, `Bridgit.act` runs on the phone as your agent, with its grants and the owner's switches, and resolves `{ok, status, body, error?}` as on the phone. The phone's log marks these calls `via mcp_app`.
- `Bridgit.respond` sends the answer into the conversation as a message and fires `bridgit:answered`. It does not answer the copy on the phone.
- The chat app connects to `http://<phone>:<port>/v1/mcp` with the agent's key, from a computer on the owner's Tailscale network (for example VS Code; a desktop chat app can connect through `mcp-remote` if it shows MCP Apps for local servers). claude.ai and ChatGPT need a public HTTPS address with sign-in, which the relay doesn't offer yet.

Details: https://bridgit.cc/learn/mcp.md.

## Timer → sense → local filter → model → history → UI

The native [DogMonitor](https://github.com/sponicgarden/bridgit-client/blob/main/app/src/main/java/cc/bridgit/app/dog/DogMonitor.kt) takes a bounded 640-pixel photo on a ten-second timer, one photo at a time. Slow work skips slots instead of stacking captures. Its [DogChecks](https://github.com/sponicgarden/bridgit-client/blob/main/app/src/main/java/cc/bridgit/app/dog/DogChecks.kt) has a separate change gate for each camera/model pair. A locally unchanged image can reuse the previous classification; a fresh sample timestamp on a reused answer is **not** the timestamp of a new model inference. Native high-sensitivity comparison uses a one-hour maximum reuse interval. Noise masking temporarily ignores regions that changed without changing the model answer. Failure clears the affected gate so it cannot certify uninterrupted continuity.

Selected Clef and/or Jev calls classify visible activity; one primary result feeds native activity totals. The latest photo stays in memory; the native tracker saves activity history locally. Native audio is a separate opt-in loop: [DogAudio](https://github.com/sponicgarden/bridgit-client/blob/main/app/src/main/java/cc/bridgit/app/dog/DogAudio.kt) records five-second samples, never continuous coverage; the audio loop waits sixty seconds after sampling the selected sources. It uses Clef, and low confidence/silence/ambiguity becomes `unclear`. Missing microphone data is explicitly an unavailable sample, never a silence reading. Its finite recent readings do not establish a full-day bark count or continuous sound log.

The public `pet-monitor` recipe uses a generic camera watch with an explicit 30-second interval, 15-minute reuse ceiling, 10 paid calls/hour cap (sized for the default monthly model budget; read `GET /v1/limits` for the account's real one), seven-day expiry, a pet-in-view condition and a 6 pm no-meal rule. The timer, capture/filter, optional decision request and tally run on the phone. Paid inference runs on the remote decision service. The offline card reads `/v1/watches/{id}/tally` through the bridge. The controller stores IDs, creates/replaces cards, archives history before deleting, and handles optional integrations. Cache/tally behavior is the generic watch implementation, not inherited from DogMonitor.

The `room-change-watcher` uses `model:none`: a first photo establishes the baseline and later image changes produce events at the configured cooldown. It does not recognize a person, pet or danger. An optional controller can ask a model about a fresh capture after a change. No model call is silently included in the free recipe.

The `situation-counter` sends `situation` (a library id from `GET /v1/situations`, or the owner's own words) instead of a question. The phone expands it into a Clef yes/no question, a tally of episodes (track = the situation id, or `situation` for own words) and, for pot_boiling_over, smoke_in_room and water_on_floor, an urgent owner notice when an episode starts. It checks every 20 seconds with the free change check on and at most 30 paid photos an hour; the card reads `/v1/watches/{id}/tally` and shows how many times it was seen today. Needs API revision 71.

## Native versus publicly composable

| Behavior | Native Pet Monitor | Public composition |
|---|---|---|
| Ten-second photo loop | Native serialized loop | Generic watch supports 10 s only for eligible camera tally with change check and explicit paid cap; starter uses 30 s |
| Per-camera/model cache | Native DogChecks, one-hour max reuse | Generic watch local gate; recipe 15-minute ceiling; not the same cache |
| Model classification | Native selected Clef/Jev, primary reading totals | Watch typed questions and tally, one selected model per watch |
| Activity history | Native tracker and saved history | Generic tally with retained episodes; export before delete, no automatic native history migration |
| Auto best-camera selection | DogCamera scoring and rotation, hysteresis/rescan rules | Controller must implement selection; setting a watch camera does not invoke Auto |
| Microphone | Opt-in five-second Clef samples, bounded recent list | Bounded `/v1/mic/record` or enrolled-camera audio plus a separate authorized server/controller decision call; not built into this watch |
| Last-seen pan search | PetCameraSearch: near-anchor bounded poses, timeout/cooldown, authorization and motor stop | Not included; generic watch never moves a camera |
| Remote owner portal | Native sharing/photo policy, authenticated viewer and native state | A card is offline UI, not a portal replacement; native viewer identity/tokens cannot be reproduced by HTML |
| Native applet reads/actions | `/v1/applets/dog-monitor`, history, cached frame; owner-approved applet actions where installed | Separate native read/configuration API; do not confuse it with the generic watch ID |
| Talk, feeding, physical device actions | Separate native/camera authorization and supported hardware | Explicit additional integration; starter performs none |

The native search starts only from a measured recent last-seen pose, waits at least a minute after loss, uses a five-minute cooldown and a thirty-minute anchor age, checks proximity to the anchor, uses at most seven nearby poses, times out after sixty seconds and stops the motor on cancellation. These are source constraints, not measurements from a real camera. Do not run physical search just to verify this document.

## Freshness, uncertainty and cost

Every reading needs its source time, observation type (new model inference, local reuse, inventory read or unavailable), and coverage. Cards explicitly show loading, empty, stale, error, paused and stopped states. Watch tally uses last_seen_at and the watch status uses last_check.at. Weather uses fetched_at/expires_at plus the service's stale flag, and preserves attribution. Device inventory is `not_checked` until a separate successful status action. OCR and transcripts are drafts requiring owner confirmation; they do not acquire a trustworthy image observation time by being rendered now.

Do not convert camera errors, occlusion, a confidence below threshold, missing audio, a skipped paid call, or a controller disconnection into a negative finding. A cached answer may generate callbacks, but it is still reused evidence. Episode totals approximate observations at a chosen interval/gap; they do not prove continuous behavior. Watch retained windows, ten recent checks and finite tally retention are bounded; they are not an unlimited event database.

Watch `usage` reports paid calls/cost, locally answered checks and skipped-over-cap checks. Limits are shared with the phone/team budget. The per-watch paid cap reduces consumption but does not reserve quota or guarantee successful inference. Check current decision documentation for prices and budgets. Camera/microphone sampling also consumes battery. No examples here are measured cost, latency or accuracy benchmarks.

On failures, inspect machine-readable errors, recent_checks, watch.ended and schedule status. Honor access revocation and owner stop. Back off transient busy/network/rate-limit errors rather than increasing sample rate. A quota/budget or terminal model failure can end a watch; start a replacement only with continuing owner authorization. Do not retry captures blindly after an uncertain transport result. Use endpoint-supported idempotency for mutations that support it and preserve the same logical key/input. Watch create is not an idempotent endpoint: inspect existing watches before retrying uncertain creation.

## Lifecycle and control verification

Watch pause/resume changes `user_paused` and preserves tally without extending expiry. There is no generic watch update/PATCH: archive the old tally, delete, recreate and rebind the card. Confirm deletion returns the saved ID, then consume watch.ended when available; final history can arrive in the end event. Deleting a watch and cancelling its interaction are separate operations. Cards stop new requests and refuse late results once stopped; a local card stop does not prove an external controller stopped.

The non-watch starter cards have no automatic background job. Pause is local UI suspension, resume allows another explicit read, stop suspends the card and sends one controller shutdown request. An in-flight capture may finish; no cancellation endpoint is invented. The controller must acknowledge any actual notebook/receipt/dashboard job shutdown separately and cancel the card with `DELETE /v1/interactions/{id}`. Never display an external action as complete just because respond was called or persisted.

Schedules belong to an agent, so inspect `/v1/schedule/manifest` and merge with existing actions. New manifests await owner approval. The daily reminder starter is bounded to seven executions. The weather card is independent: pausing the card does not pause the reminder schedule. `/v1/schedule/pause` affects the agent’s whole manifest. To remove only this recipe from a shared manifest, submit a replacement without that action and wait for approval. Only DELETE the entire schedule when dedicated to this recipe; `keep_assets=true` preserves saved files. Check `active` digest, next run and last status, never assume `awaiting_owner` means active.

## Validation and future measurements

The generator verifies all request operation refs against generated OpenAPI, checks required fields/types/enums/query names, bounded recipe watch settings and evidence paths, and regenerates complete offline HTML bodies. Generated files are compared byte for byte in tests. Card script tests mock the real `{ok,status,body}` bridge shape, check acknowledgement/failed stop/paused/stale behavior, and prove no call happens on load. OpenAPI has intentionally shallow nested request and descriptive response contracts; Node checks alone are not proof of Android handler acceptance. The recipe Kotlin tests additionally pass watch/schedule examples through canonical pure parsers and card HTML through CardRules. Synthetic responses illustrate actual source shapes; they are not captured device responses or complete runtime smoke tests.

Before promoting a real integration, collect owner-authorized labeled samples, expected/actual answers, missing coverage, source age/cache reuse, paid calls/cost, stop/pause confirmations and controller/device/server connectivity. Keep contract checks separate from task-success evidence. Do not claim physical camera testing, real notebook persistence, live OCR accuracy or benchmark success unless it was actually observed. Add a future recipe by updating authored metadata, using existing API schemas/IDs and publication tests, documenting every lifecycle boundary, then attaching evidence from separately authorized functional testing.

## Required practice contract

Before creating or changing an applet, read [the practice contract](https://bridgit.cc/learn/builder/applet-practices.md) and [catalog](https://bridgit.cc/learn/builder/practices.json). Record the catalog review in `ui.practices`. The validator reports applicable practices, defaults and checks. Run the named behavioral checks; unverified is not passed. Use a justified exception only for its exact target. Reassess exceptions when the target changes.
