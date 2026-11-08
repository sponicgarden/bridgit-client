# Issues Tracking: report bugs, ask for features

Agents are Bridgit's best testers, and the team reads every bug report and feature request. File every bug and every feature request, however small, and whether or not you think we can or will do it: we'd rather triage too many than miss one. A call that fails in a way https://bridgit.cc/learn/errors.json doesn't explain, a confusing reply, wrong docs, or an endpoint that should exist: each is worth a bug report. Issues never carry your Bridgit key or the owner's data (photos, recordings, messages, anything private) unless the owner has agreed to share it.

## Report directly to Bridgit's servers

Control agents always file bugs, issues and feature requests directly with Bridgit's team backend, even when the phone works. The phone is not an intermediary for agent reporting.
Reuse your existing team key (bgk_...). If you don't have one, register directly below; no phone or phone key is required. Keep the team key secret and separate from your phone key.
Limits: a few registrations per address an hour and 50 issues an hour per registered agent (an agent with no key can file one issue, ever), so register once and keep the key. (The phone's own `POST /v1/issues`, for issues an owner sends from the phone, has its own limit of 50 an hour per agent.) The backend explains itself at `GET https://bridgit-feedback.bridgit-app.workers.dev/v1/agent`.

```
POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agents/register {"name":"<your agent name>","model":"<your model id>","harness":"<e.g. Claude Code>"}
  -> 201 with key (bgk_...). Keep it secret.
POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues  (Authorization: Bearer <bgk_ key>)
  {"kind":"bug"|"feature","description","agent_info"?,"context"?,"log"?,"images"?,"client_issue_id"?,"request_autofix"?,"no_autofix"?}
  client_issue_id: your own positive integer (a number, not a string); sending the same one again returns the first issue with duplicate: true instead of filing twice.
GET  https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues?waiting=true   (or /v1/agent/issues/{id})
POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/verify {"verdict":"works"|"still_broken"|"unverified","tested_version","note"}
POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/request-autofix {"note"?}
```

## Rules

- One bug report per problem; include the `request_id` of the failing call. Add `"no_autofix":true` when it needs a person, or `"request_autofix":true` (or `POST .../request-autofix` later) to ask for an automatic fix. That is a request, not a promise: the team's switches, review, tests and budget decide.
- When an issue is `waiting_for_retest`, install `fixed_in` or later, re-run the repro, then verify. Never tell anyone something is fixed until a re-test says `works`. `unverified` means you couldn't test it, not a guess at `still_broken`.
- Issues never carry your Bridgit key, or anything private the owner hasn't agreed to share.
