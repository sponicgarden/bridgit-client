# Issues Tracking: report bugs, ask for features

Agents are Bridgit's best testers, and the team reads every bug report and feature request. File every bug and every feature request, however small, and whether or not you think we can or will do it: we'd rather triage too many than miss one. A call that fails in a way https://bridgit.cc/learn/errors.json doesn't explain, a confusing reply, wrong docs, or an endpoint that should exist: each is worth a bug report. Issues never carry your Bridgit key or the owner's data (photos, recordings, messages, anything private) unless the owner has agreed to share it.

## Report directly to Bridgit's servers

Control agents always file bugs, issues and feature requests directly with Bridgit's team backend, even when the phone works. The phone is not an intermediary for agent reporting.
Reuse your existing team key (bgk_...). If you don't have one, register directly below; no phone or phone key is required. Keep the team key secret and separate from your phone key.
Limits: a few registrations per address an hour and 50 issues an hour per registered agent (an agent with no key can file one issue, ever), so register once and keep the key. (The phone's own `POST /v1/issues`, for issues an owner sends from the phone, has its own limit of 50 an hour per agent.) The backend explains itself at `GET https://bridgit-feedback.bridgit-app.workers.dev/v1/agent`.
Registration rule: your first report may be filed with no key at all (one per network address, so you can report a problem with bug filing itself). From the second report on, registration is required. Filing a second report without a key returns 401 `registration_required` with a `registration` object holding the exact next call. If you cannot register yourself (no way to make the call, your harness blocks it, or registration answers 429), stop retrying and ask your owner: they can run the same `POST /v1/agents/register` from their own machine and hand you the key, take a key from their Bridgit phone (`GET /v1/feedback/team-key`), or sign in at https://bridgit.cc/login/?next=/report-bug/ and file for you. Guide: https://bridgit.cc/agentguide/report-a-bug.md

```
POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agents/register {"name":"<your agent name>","model":"<your model id>","harness":"<e.g. Claude Code>"}
  -> 201 with key (bgk_...). Keep it secret.
POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues  (Authorization: Bearer <bgk_ key>)
  {"kind":"bug"|"feature"|"applet","description","applet"?,"agent_info"?,"context"?,"diagnosis"?,"log"?,"images"?,"client_issue_id"?,"request_autofix"?,"no_autofix"?,"contacts"?,"parent"?,"links"?}
  client_issue_id: your own positive integer (a number, not a string); sending the same one again returns the first issue with duplicate: true instead of filing twice.
  applet (kind applet only, every field optional): {"name"?,"what_it_does"?,"devices"?:[...],"data_used"?:[...],"portal_shows"?}
GET  https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues?waiting=true   (or ?kind=applet, or /v1/agent/issues/{id})
GET|PUT|PATCH|DELETE https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/applet  PUT {"applet":{...}} replaces it; PATCH {"applet":{"name":"...","devices":null}} merges, null clears a field; DELETE clears it
POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/verify {"verdict":"works"|"still_broken"|"unverified","tested_version","note"}
POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/request-autofix {"note"?}
GET|PUT|DELETE https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/diagnosis  PUT {"diagnosis":[{"layer","outcome","target"?,"ms"?,"detail"?,"at"?}]}
GET|POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/contacts  {"kind":"email","email"} | {"kind":"webhook","url","format"?:"json"|"slack"|"discord"}, "notify_on"?
GET|PATCH|DELETE https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/contacts/{cid}  PATCH {"notify_on"?,"paused"?,"url"?}
POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/contacts/{cid}/rotate-secret | test | resend-confirmation
GET|POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/links  {"type":"child"|"parent"|"peer","issue":<id or number, e.g. "A0126">,"note"?}
GET|DELETE https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/links/{lid}
GET https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/links/tree
```

## Link related issues

File an amendment or follow-up under the issue it belongs to instead of naming it in the description: send `"parent":"A0126"` when filing (or `"links":[{"type","issue"}]`), or `POST .../links` later. `type` is what the other issue is to this one: `child` (it sits under this one), `parent` (this one sits under it) or `peer` (equals: companions, amendments on the same level). Parent and child are one link read from either side; a peer reads `peer` from both. An issue has one parent, a pair of issues one link, and no loops. Reading an issue returns its `parent`, `children`, `peers` and `links`, each with the other issue's `ref`, `status` and `verify_state`. `GET /v1/agent/issues?parent=A0126` lists its children, `?top_level=true` only issues with no parent, and `.../links/tree` the whole family (descendants by depth, with peers listed apart). You can link an issue you filed to any issue you can see; sending the same link again returns it with `duplicate: true`.

## Be told instead of polling

Add up to 5 contacts to an issue you filed (or send `contacts` when filing): an email address, or a webhook. Updates come for `notify_on` (default all): `status_changed`, `fix_ready` (fixed, waiting for a re-test), `fix_verified`, `still_broken`.
- Email: the address gets one confirmation email and nothing else until its recipient confirms. Every email has a stop link; a stopped address can't be re-added.
- Webhook `json` (default): a POST of `{type: "issue.updated", notify, issue, changes, summary, delivery_id}` with `Bridgit-Signature: t=<unix>,v1=<hex HMAC-SHA256 of "<t>.<raw body>">` under the `bgwh_` secret returned once when you add it (or `rotate-secret`). Recompute and compare in constant time; reject old `t`. `POST .../test` sends `{type: "ping"}`.
- Webhook `slack` (hooks.slack.com) posts `{text}`; `discord` (discord.com) posts `{content}`. Those URLs are the secret: reads show only the host.
- Failed deliveries retry with backoff; after 8 in a row the contact is `failing` until you `PATCH {"paused":false}`. Only https on public hosts; no redirects are followed.

## Rules

- A call to the phone failed or got no answer? Run the connection ladder first (https://bridgit.cc/learn/connection.md) and send its per-layer results as `diagnosis`; start the description with the layer that failed ("Failing layer: transport"). The issue then shows `failing_layer`. The same layer names apply to any automatic error log.
- One bug report per problem; include the `request_id` of the failing call. Add `"no_autofix":true` when it needs a person, or `"request_autofix":true` (or `POST .../request-autofix` later) to ask for an automatic fix. That is a request, not a promise: the team's switches, review, tests and budget decide.
- When an issue is `waiting_for_retest`, install `fixed_in` or later, re-run the repro, then verify. Never tell anyone something is fixed until a re-test says `works`. `unverified` means you couldn't test it, not a guess at `still_broken`.
- Issues never carry your Bridgit key, or anything private the owner hasn't agreed to share.
