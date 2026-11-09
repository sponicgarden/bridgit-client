# When a call fails: the connection ladder

Run this whenever a call to a Bridgit phone fails or gets no answer, before retrying, guessing or filing a bug. "curl: http=000" can mean the phone is off the tailnet, its Tailscale stopped, Bridgit isn't running, or Bridgit is running but stuck: four different problems with four different fixes. Check one layer at a time, from the bottom up, and stop at the first one that fails: that is where the connection is broken. Every check is read-only and cheap; none captures anything or changes a setting.

Script: https://bridgit.cc/learn/connection.sh runs layers 1 to 4 and prints one result per layer (`HOST=<phone Tailscale IP> PORT=<port> HDR=<your header file> sh connection.sh`; it needs curl, and `tailscale` for layer 1). It sends your key only to the phone and never prints it.

## The layers

| # | Layer | Question | Check | Fails as |
|---|---|---|---|---|
| 1 | `network` | Is the phone on the tailnet? | `tailscale ping -c 1 --timeout 5s <ip>` (or the peer's Online in `tailscale status --json`) | `off_tailnet` (no matching peer), `peer_offline` (listed, no pong) |
| 2 | `transport` | Does anything accept TCP on the Bridgit port? | `curl -s -o /dev/null -m 5 -w '%{time_connect}' http://<ip>:<port>/`; exit 7 = refused, exit 28 with time_connect 0 = no TCP answer | `refused` (phone up, nothing listening), `timeout`, `no_route` |
| 3 | `app` | Does Bridgit answer `GET /v1/health`, how fast? | `curl -s -m 10 -H @<header file> -w '%{http_code} %{time_total}' http://<ip>:<port>/v1/health` | `no_answer` (connected, no reply: wedged), `http_5xx` (up but erroring), `not_bridgit` |
| 4 | `auth` | Is it the key? | If health answered 401 or 403, call it once more without the header and compare | `key_rejected` (401 with and without the key: wrong or revoked), `api_access_off` (403, reason api_access_off), `email_required` |
| 5 | `capability` | Health is green but the real call failed: can this feature act? | `GET /v1/capabilities` (`agent_allowed`, `enabled`, `ready`, `blocked_reason`), then `GET /v1/log?agent=me` for the failed `request_id`. Repeat the action only if the owner asked for it. | `not_allowed`, `disabled`, `not_ready`, `action_failed` (with the error code) |

What each result tells the owner:
- `network` fails: the phone is off the tailnet. Its Tailscale may be stopped (a Play update can stop it; Bridgit asks it to reconnect on its own), or the phone is off or asleep. Ask the owner to open Tailscale on the phone. Not on the tailnet yourself? Ask for More → Relay setup.
- `transport` fails with `refused`: the phone is reachable but Bridgit isn't listening. Bridgit is stopped or paused, or still rebinding after the address changed. Ask the owner to open Bridgit; it starts listening by itself.
- `transport` fails with `timeout` while `network` passed: the address or port is wrong (compare `base_url` from your last health), or the phone's Tailscale stopped between the two checks.
- `app` fails: Bridgit accepted the connection but didn't answer in time (`no_answer`) or answered with an error. Wait 30 s and repeat once; then file a bug with the `request_id` if any.
- `auth` fails: the app is fine; your key isn't. Don't retry with the same key: ask the owner to check the agent under Agents, or to pair you again.
- `capability` fails: the connection is fine; that one feature can't act. Name it (its `title`) and `blocked_reason` to the owner.

## What health says about the listener

Bridgit listens only on the phone's Tailscale addresses, never on the LAN, so "reachable on Wi-Fi" does not mean reachable for agents. `GET /v1/health` has a `listener` block: `state` (`listening`, `rebinding`, `waiting_for_tailscale`, `bind_failed` or `stopped`), `binds` (`tailscale`), `bound_to` and `port`, `tailscale_addresses` (what the phone has right now), `on_tailnet`, `tailscale_installed`, `magic_dns` and `bind_error`. `bound_to` differing from `tailscale_addresses` means Bridgit is moving to a new address: use the new one in a few seconds. Read over the relay, the block tells you whether the Tailscale side is down while the app is fine.

## Over the relay

No tailnet: layer 1 is `GET <relay address>/quota` (it answers without using a job; 401 means your `rly_` token, which is `auth`). Layer 2 is a `POST <relay address>/jobs?wait=30` for `GET /v1/health`: `phone_unreachable`, or a `202` that never completes, means the phone's relay connection is down. Layer 3 is that job's answer, and its `listener` block as above.

## The result to record and file

One JSON object per layer you ran, in order:

```
{"layer": "network|transport|app|auth|capability", "outcome": "pass|fail|skip", "target": "<phone ip>:8734", "ms": 41, "detail": "refused: nothing listens on port 8734", "at": "2026-11-08T16:41:00Z"}
```

`skip` is for a layer you couldn't run (no `tailscale` command, userspace networking) or didn't need after an earlier failure. Start `detail` with the failure word from the table. Never put your key, a token or the owner's data in a result.
Filing a bug (https://bridgit.cc/learn/bugs.md): send the results as `"diagnosis": [ ... ]` with `POST https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues`, and begin the description with "Failing layer: <layer>". The issue then reports `failing_layer` (the first layer that failed; `none` if none failed). Fix or extend them later with `PUT https://bridgit-feedback.bridgit-app.workers.dev/v1/agent/issues/{id}/diagnosis`; `DELETE` clears them. Keys and tokens are removed before they are stored.

## Example

8 Nov 2026: every route to a Pixel answered http=000. Layer 1 passed (`tailscale status` listed the phone at its address) while nothing answered on the Bridgit port: the break was on the phone's app side, not the device or the network. The owner confirmed Bridgit wasn't running.

```
[{"layer":"network","outcome":"pass","target":"pixel-10-pro-xl <phone ip>","detail":"listed in tailscale status"},
 {"layer":"transport","outcome":"fail","target":"<phone ip>:8734","detail":"timeout: http=000 on every route"},
 {"layer":"app","outcome":"skip"}, {"layer":"auth","outcome":"skip"}]
```
