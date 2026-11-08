# Link two Bridgits

Two of the owner's Bridgits can be linked, so an agent that holds a key for one can use the other through it. A link has two directions: **in** (the other Bridgit uses this one) and **out** (this one uses the other). Each direction works only when **both** Bridgits opted in: the one being used lists what may be used there (its **services**), and the one using it lists which of its agents may reach it. Either Bridgit can take its half back at any time, and every change is logged on both, with who made it.

The person's only step is to tell you the other Bridgit's **Device Domain Name** and its **link code** (or show you its QR code). Everything else is yours to do, on each Bridgit with that Bridgit's own agent. You need the `owner.configuration` grant on both.

## 1. Both Bridgits need a name

`GET /v1/health` shows `bridgit_name`. If it says `unregistered`, register one first (see https://bridgit.cc/agents/names/): `POST /v1/owner/configuration/name {"name":"kitchen-tab"}`. Without a name, the link calls answer `409 name_required`.

A named Bridgit also stores its static spec sheet with Bridgit (on by default; the owner can switch it off): display size, the full-screen card size in each orientation, cameras, CPU, memory, storage, sensors, model and OS. For static specs (screen size, aspect ratio, cameras), ask Bridgit first with `GET https://<team backend>/v1/names/{name}/specs` (the exact URL is `bridgit_name.specs` in health): it answers while the phone is busy or offline. Query the phone only for live state. Its `full_screen_image` is the size to compose a full-screen picture for; post it in an html card with `presentation: "full_screen"`. The owner configuration operation `GET`/`POST /v1/owner/configuration/specs` (`{"share":false}`, `{"share":true}` or `{"refresh":true}`) reads and changes sharing.

## 2. On the Bridgit that will be used: make a code

```
POST /v1/owner/configuration/links/codes
Idempotency-Key: <new uuid>
{"services":["camera","mic","speaker","interactions","view"]}
```

`services` is what the other Bridgit may use here. Values: `camera`, `mic`, `speaker`, `interactions`, `location`, `gallery`, `sounds`, `view` (Home, applets and photo submission). Nothing passes until you list it. `directions` defaults to `["in"]`; add `"out"` (with `agents`: this Bridgit's agent ids) if this Bridgit should also use the claiming one.

The reply has `name`, `code` (like `K7QX-3MPA`), `expires_at` and `link` (`bridgit://link#…`, for a QR code). The code works **once**, for **10 minutes**, and **5 wrong tries** void it. Pass `name` and `code` (or `link`) to the agent on the other Bridgit, or ask the person to.

## 3. On the Bridgit that will use it: claim the code

```
POST /v1/owner/configuration/links/claims
Idempotency-Key: <new uuid>
{"name":"porch-cam","code":"K7QX-3MPA"}
```

Optional: `agents` (this Bridgit's agent ids that may use it; default: you), `reverse_services` (what the other Bridgit may use here in return), `link` instead of name and code, or `host` and `port` (its Tailscale address) when the names backend can't find it. The claim is this Bridgit's opt-in. The names backend gives the address only for a proof of the code; it never sees the code.

The reply is the link: `id` (`lnk_…`), `target` (what you name in calls), and `in` and `out` with their `state`: `on`, `waiting_for_target`, `waiting_for_user`, `paused` or `off`.

## 4. Use it

Add `X-Bridgit-Target: porch-cam` (or `?target=porch-cam`) to any call whose capability is in the services:

```
POST /v1/camera/snapshot?target=porch-cam {"camera":"rear"}
```

`GET /v1/links` lists the linked Bridgits you may use (`GET /v1/satellites` is the same list). Old `sat:` ids keep working as targets. The answer carries `X-Bridgit-Ran-On`. A call is passed on **once**: a call that came over a link can't name another target (`403 not_on_target`). To use a third Bridgit, link it directly.

## 5. The other direction

If you sent `reverse_services`, the other Bridgit's `out` is `waiting_for_user`. On that Bridgit, its own agent opts in with the agents that may reach this one:

```
POST /v1/owner/configuration/links/{id}/directions/out {"agents":["<agent id>"]}
```

Change this Bridgit's half any time: `POST …/links/{id}/directions/in {"services":[…]}` (what the other may use here), `…/directions/out {"agents":[…]}` (who here may use it), `{"paused":true}` on either.

## 6. Disconnect

Any control agent of a Bridgit may take access away, with no `owner.configuration` grant:

```
POST /v1/owner/configuration/links/{id}/disconnect {"direction":"in"}
```

`direction` is `in`, `out` or `both` (default); `{"pause":true}` pauses instead. Calls are refused at once; it becomes final after 10 seconds unless `POST …/links/{id}/undo` runs first. After that, linking again needs a new code. `POST …/links/disconnect-all` cuts every link. It works with the other Bridgit offline: its next call gets `403 link_revoked` and it marks its side off.

## 7. Who did what

`GET /v1/owner/configuration/links/log` (filters `link`, `direction`, `granter`, `before`, `limit`) lists every link event on this Bridgit and those the other one reported (`reported_by`, `late` when sent after being offline), each with its `granter`: a person, an agent (id, name, device), a rule, or `migrated` for links from before linking existed.

## Errors

| HTTP | error | Meaning |
|---|---|---|
| 409 | `name_required` | Register a Device Domain Name first |
| 403 | `code_invalid` | Wrong code; 5 wrong tries void it |
| 410 | `code_expired` / `code_used_up` | Ask for a new code |
| 404 | `unknown_target` | No linked Bridgit by that name |
| 403 | `target_not_granted` | You aren't in this Bridgit's `out` agents |
| 409 | `link_waiting` | One side hasn't opted in yet (`waiting_for`) |
| 403 | `link_paused` | Paused; wait |
| 403 | `link_revoked` | Disconnected; a new code is needed |
| 403 | `not_on_target` | That call doesn't pass a link |
| 503 | `target_offline` | The other Bridgit didn't answer; retry later |

Over MCP, the same operations are `configuration.get` and `configuration.set` with `operation` set to the method and path above.
