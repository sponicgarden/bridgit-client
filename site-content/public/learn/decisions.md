# Designing applets with decisions

Read this before you build something on Bridgit that has to decide: whether to interrupt the owner, whether a sound
matters, whether the dog is on the couch. Bridgit exposes general decision services that any applet can select. Query `GET /v1/services` (MCP `services`) for the catalog, exact calls, grants and budgets. You send a state (text, JSON, or
media) and typed questions; you get back typed answers, each with a probability or a confidence. No prose to parse,
so "act only when at least 80% sure" is one rule.

- **GLM 5.3 Flash** (OpenRouter, `z-ai/glm-5.3-flash`): choose `model: "glm"` for general text/JSON and photo decisions in any applet. Use it through `POST /v1/judgment`, a watch, or a flow `ask_model` step. It is not reserved for Chef’s Helper or a particular template.
- **Jev** (TypeSafe, `jev-1.14.0`): text and JSON, and up to 2 small images. Best at reasoning about text.
- **Clef-omni** (Cloudflare): photos and screenshots, sound clips and short videos, plus text. The only one that hears
  and watches.

For location context, `GET /v1/services` also lists Nearby Places, Air Quality, Pollen, Time Zone, satellite maps, Street View, Google Weather, OpenStreetMap, Wikipedia and Open-Meteo. Each has an exact `POST /v1/services/context` request, provider/key requirements, costs and attribution. Static public contracts: https://bridgit.cc/learn/context-services.json. Read location separately with its grant; context calls require explicit coordinates and the judgment grant. Enabling a Google API in Cloud Console is not proof that its key is configured in Bridgit.

Keys are write-only through `GET/POST /v1/settings/ai-keys` for `googlemaps`, `openmeteo` and `openrouter`, with the existing authenticated configuration authority. Never put keys in applet HTML. A configured own key is used for that provider; otherwise the shared backend key is used if configured. Shared usage follows the existing account, applet and service spend limits. Google monthly free allowances belong to the provider billing account, not each applet user; the catalog's published allowances are a dated pricing snapshot, not live remaining balances or automatic hard cutoffs. Conservative paid-price reserves are charged to Bridgit budgets even when the provider's free allowance may cover the call. Missing keys, exhausted budgets and unavailable providers return explicit errors; do not silently substitute another source.

Google content remains subject to Google attribution, caching, AI and text-to-speech reuse terms. Provider access is not permission for all downstream uses. Keep nearby mapped information separate from current camera observations. Air quality describes an area and does not establish a person's health or visible pollution.

For the registry edition of **A Sorta Fairytale About Me**: record 20 seconds with `POST /v1/camera/video` and `extract_frames:6` to get six caller-accessible JPEG capture ids without returning large video bytes. Use `POST /v1/camera/snapshot` with `store:["memory"]` and `return_image:false` for a portrait capture id. Call `POST /v1/story/generate` with `frame_capture_ids`, `portrait_capture_id`, `mode:"kind"|"wild"`, and optional already-read context. GLM 5.3 Flash first inventories the frames, then writes a spoken story. The card saves title, narration and mode through `POST /v1/applet/tables/stories/rows`. A private fork writes to its own instance. `POST /v1/story/stop {}` discards late results and stops this caller's audio/video. Narration resolves the applet speech registry selection, requires Aura 1 and explicit cloud permission, and queues sentence-bounded chunks of at most 600 characters. The intro is https://bridgit.cc/assets/audio/a-sorta-fairytale-about-me-intro.mp3 (15 seconds, source 00:52–01:07). Video extraction, location lookup, context calls, writing and narration each require their normal grants; discovery creates none.

Use a chat model instead for other open-ended summaries, replies and explanations.

## Pick the route

| You want to | Call | Model | Media | What runs |
|---|---|---|---|---|
| Decide once about text or a sound, and have the phone act | `POST /v1/judgment` on the phone | GLM Flash (`model: "glm"`), Jev, or Clef with an `audio_clip_id` from a sound or wake-word event | a heard sound clip | The phone runs the matching notice, speak or log actions as you |
| Be called back when the phone's readings or camera match | `POST /v1/watches` on the phone | Clef (default with a photo), Jev (default without), GLM Flash (`model: "glm"`), or `none` (local only, free) | sensors and a camera photo | The phone checks every 30 s to 1 h (every 10 s for a camera tally with the free change check), skips unchanged photos for free, can count (`tally`) and act (`rules`) with you offline, and calls your `callback_url` |
| General GLM Flash decisions from anywhere | `POST {base_url}/v1/glm` with your phone-issued team key | GLM 5.3 Flash | up to 2 images | Typed answers and matching actions |
| Decide about text or a photo from anywhere | `POST {base_url}/v1/jev` with your team key | Jev | up to 2 images | Matching actions come back as a list for you to run |
| Decide about photos, screenshots, sound or video | `POST {base_url}/v1/clef` with your team key | Clef-omni | 4 images, 4 audio clips, 2 videos | Matching actions come back as a list for you to run |

Get `base_url` and your team key (`bgk_...`) from `GET /v1/issues/team-key` on the phone. Self-registered keys
can't use these paid services. All decision routes take the same `actions` rules as `/v1/judgment` (bridgit-actions/1).

Service budgets are account-wide and apply alongside applet limits, including own-key calls. Read `GET /v1/services/budgets`; propose changes with `/v1/owner/configure` action `service.budget.set` (exact owner approval required). Check the selected call's grants, switches and prerequisites before using it; a service listing never authorizes a call.

## Free first: the change check on the phone

Most photos of a door, a couch or a desk look like the one before. Don't pay a model to say so again. Before a
camera watch sends a photo to Clef or Jev, the phone shrinks it to a 32 x 32 grayscale grid and compares it with the
last photo a model was asked about. Only one solid patch of change counts: flickering leaves, sensor noise or the
camera adjusting its exposure don't. While nothing solid changed, the last paid answer is reused for free
(`answered_locally: true`); after `max_reuse_s` (default 300) the model is asked again anyway. It's on by default
for every watch with `camera` in `data`, costs nothing and doesn't count toward any limit.

| You want | Watch settings | You pay |
|---|---|---|
| A typed answer about the picture ("is someone at the door?") | `"model": "clef"` and a question; the change check stays on | Only when the picture changed (or `max_reuse_s` passed) |
| Just "something changed", you decide what it was | `"model": "none"`, no question | Nothing; you pay only for what you check yourself afterwards |
| Every photo judged, even an unchanged one | `"local": {"change_check": false}` | Every reading: rarely worth it |

`local.sensitivity`: `low` (big changes only), `normal`, or `high` (smaller changes, so it pays more often).
`local.learn_noise` (on by default): when a paid call for a change gave the same answer as before, those spots are
ignored for 10 minutes, so a TV or a swaying plant stops costing money. `max_paid_per_hour` caps the bill.

### Example: "tell me when someone is at the door"

The owner props the phone up facing the door. You start a watch:

```json
POST /v1/watches
{"question": "Is a person at the front door?", "data": ["camera"], "interval_s": 30, "model": "clef",
 "local": {"sensitivity": "normal"}, "max_paid_per_hour": 30, "repeat": true, "cooldown_s": 600,
 "expires_in_s": 28800, "callback_url": "http://my-mac.your-tailnet.ts.net:8080/bridgit"}
```

1. Every 30 s the phone takes a 640 px photo. The first one goes to Clef (paid) and becomes the reference.
2. An empty doorway looks the same: the next photos reuse Clef's "no" for free. Over an 8-hour day that's most of them.
3. Someone walks up: the picture changed, so Clef is asked (paid). Its "yes" reaches `min_probability` (default 0.8).
4. You get a `watch.matched` event (in `/v1/events`, your registered callback, and a POST to `callback_url` signed
   with the `callback_secret` from the reply) with `answers`, `answered_locally`, `cost_usd` and `usage`.
5. Then act: show the owner a card with a fresh `POST /v1/camera/snapshot`, or record a 10 s clip and ask Clef to
   confirm first. `GET /v1/watches/{id}` shows `usage` (free checks vs paid calls and their cost);
   `DELETE /v1/watches/{id}` stops it.

The free-only version: the same body with `"model": "none"`, without `question` and `max_paid_per_hour` (both are
refused with `none`). The phone calls you back whenever the
picture changes (`change.largest_patch_share`), and you decide whether one `POST {base_url}/v1/clef` on a fresh
snapshot is worth it. Use it when changes are rare, or to try a spot before paying for anything.

### Count and act with you offline: tally and rules

A watch with `tally` counts on the phone for up to 90 days ("how many times did the dog eat today", with episodes and
watched time), and up to 5 `rules` act on that count with no agent online: `{fewer_than: 1, of: "eating", by: "18:00"}`
(once a day, phone's local time), `{none_for_s, of}` or `{at_least, of, within_s}`. A rule that fires sends you a
`watch.rule` event and runs its `do`, one of your own `/v1` calls, exactly as a card's `Bridgit.act` would: a notice
card, `/v1/speaker/say`, the flashlight. Add `seen` (a separate `noul` question, a yes/no answered with a probability, such as "Is an animal visible?") so
counts and gaps cover only the time the subject was in view. An HTML card reads `GET /v1/watches/{id}/tally` with
`Bridgit.act` to show it. Complete example: https://bridgit.cc/recipes/pet-monitor/recipe.json.

## Which model for which input

| Input | Use | Why |
|---|---|---|
| Text, JSON, sensor readings | Jev | Clearly more confident on text. On the battery example below, Jev was 93% sure; Clef-omni 66%. |
| A screenshot or photo, one decision now | Clef-omni, or Jev with an image | Both labelled 5 of 5 phone screenshots right. Clef-omni takes bigger images; Jev's image input is still marked experimental by TypeSafe. |
| A sound (alarm, doorbell, glass, a voice) | Clef-omni | Jev can't hear. Clef-omni told a spoken delivery message from an alarm beep (88% and 85% confident). |
| A video or screen recording | Clef-omni | Jev can't watch. Clef-omni followed a 9 s recording across three screens. |
| Hard reasoning (several facts, arithmetic, rules) | Jev | Clef (27B) scores far lower on reasoning benchmarks (GPQA Diamond 48 vs 78); Clef-omni is smaller. |

When a decision needs both (a photo and a rule about the owner's calendar), ask Clef-omni what's in the photo,
then put its answer into the state you send Jev.

## Write questions that work

This example goes to `POST {base_url}/v1/clef` (the `images` field is Clef's; Jev uses `assets`).

```json
{
  "state": {"screen": "see image", "time": "14:05", "focus_mode": "work"},
  "images": ["data:image/jpeg;base64,..."],
  "questions": {
    "activity": {"type": "choice", "instructions": "What is the owner doing on the phone?",
      "criteria": {"call": "on a voice or video call", "signing": "signing or paying for something",
                   "game": "playing a game", "reading": "reading or browsing", "other": "none of these"}},
    "busy": {"type": "noul", "instructions": "Would a notification right now interrupt something important?"}
  },
  "actions": [
    {"id": "hold", "when": {"answer": "busy", "min": 0.7}, "do": {"type": "log", "message": "Held the card: owner is busy."}}
  ]
}
```

- **Typed, not open.** `noul` gives the probability of yes (0 to 1). `choice` picks one option and gives a
  probability for each plus a `confidence`. Clef also has `score` (a rating on a scale of 2 to 10 levels), but
  action rules don't match scores yet.
- **Describe every option** in `criteria`, and always include a way out (`other`, `nothing`, `none of these`).
  Without one, the model has to pick something.
- **One thing per question.** Ask "is there a person?" and "is the person carrying a box?" separately, not together.
- **Point at the data.** In Jev, refer to state fields in backticks (`` `screen` ``) and to images by their asset
  reference. In Clef, images, audio and video come before the state in the order you send them; say "the first image",
  "the clip".
- **Put context in the state**, not in the question: the owner's preferences, the time, what "urgent" means for them.
- **Up to 50 questions** per Jev call and 64 per Clef call. Ask several questions about one state in one call: they share
  the state's cost (you pay for input only, so each extra question adds just its own text).

## Pick thresholds

- **Confidence is not probability.** For a `choice`, `confidence` is lower than the winning probability: a 67%
  likely answer is only about 34% confident, and on the battery example Clef-omni said yes at 91% probability
  but 66% confidence. Use `min_confidence` 0.8 for choices you act on, or test `min` on a `noul` probability.
- **Start at 0.8** for anything the owner sees or hears; use 0.9 for interruptions at night. Use `log` actions to
  record the near-misses (0.5 to 0.8) and look at them before lowering a threshold.
- **Media answers are less certain.** Sound and video answers came back at 81% to 88% confidence in tests: they
  pass 0.8, not 0.9.
- **Test with real samples.** Before shipping, run 10 to 20 real captures (with known answers) through your
  questions and check where the right and wrong answers fall.
- **Even 95% is wrong one time in twenty.** Show, suggest, or log; leave anything costly or irreversible to the owner.
  If the model fails or times out, nothing runs: design for "no answer".

## Prepare media

| Capture | Phone call | Then |
|---|---|---|
| Screen | `POST /v1/screen/capture?max_edge_px=1024&quality=70` | Clef: send as `data:image/jpeg;base64,...` (Jev: also fine, 1,568 px at most) |
| Photo | `POST /v1/camera/snapshot {"max_width_px": 640}` | 640 px keeps Jev images under its 300 KB limit and cuts Clef tokens |
| Video | `POST /v1/camera/video {"duration_seconds": 10, "resolution": "720p"}` | Clef only. The phone records 1 to 30 s (40 MB over HTTP, 6 MB over MCP); Clef takes up to 16 MB per video and reads 2 frames a second. Silent unless you add `"audio": true` (needs the microphone grant) |
| A loud sound | the `sound.heard` event, then `GET /v1/sounds/clip/{id}` | Clef only. Up to 10 s, sent as `data:audio/...` |
| A wake word | `GET /v1/listen/clip/{id}` | Clef only. Classify the request straight from audio |

- **Clef:** base64 data URLs or `{content_type, base64}`, never links. Up to 4 images (PNG, JPEG or WebP, 4 MiB each, 8 MiB
  together), 4 audio clips (8 MiB each), 2 videos (16 MiB each); audio and video together 16 MiB; the whole body 24 MiB. Too big gives 413 `payload_too_large` before anything is billed.
- **Jev images:** `assets` map, PNG or JPEG, 280 to 1,568 px per side, 300 KB each, 2 per call. Rotate photos upright
  first: Jev ignores EXIF rotation.
- **Smaller is cheaper.** A 640 px photo is about 1,550 Jev tokens; a screenshot about 600 to 760 Clef tokens; a
  second of video about 250 to 300.

## Cost

You pay for input only; answers are free. Jev is $0.042 and Clef-omni $0.15 per million input tokens.

| One call sends | Tokens | Per call | Per day, every 10 s | Per month, every 10 s | Per month, every minute | Per month, every 5 min |
|---|---|---|---|---|---|---|
| Jev, a text question | ~200 | $0.000008 | $0.07 | $2.18 | $0.36 | $0.07 |
| Jev, a 640 px photo | ~1,550 | $0.000065 | $0.56 | $16.87 | $2.81 | $0.56 |
| Clef-omni, a screenshot or photo | ~700 | $0.000105 | $0.91 | $27.22 | $4.54 | $0.91 |
| Clef-omni, a 6 s sound clip | ~250 | $0.000037 | $0.32 | $9.72 | $1.62 | $0.32 |
| Clef-omni, a 10 s video | ~3,000 | $0.00045 | $3.89 | $116.64 | $19.44 | $3.89 |

Each reply has `usage.input_tokens` and `cost_usd`, so measure your own calls rather than trusting this table.
Only check as often as the decision needs: trigger on an event (a loud sound, a screen change, motion) instead of
polling, and check video only after a cheaper photo check says something is happening.

## Limits

Limits differ per account and can change (subscription level, team arrangements and more), so never plan from
numbers in a document. Read them on the phone with `GET /v1/limits`: each comes with its `value`, what is `used` and
when it `resets_at`. The numbers below are only today's defaults, to give a sense of scale.

- **Per phone (its agents share it):** `model_calls_per_hour`, Jev and Clef calls together (default 400; 60 on a
  phone the team couldn't verify, which includes test builds), and `model_usd_per_month`, the monthly budget
  (default $1.50, or $3 on a phone with the Pet monitor's web portal on; calendar month, UTC). It is separate from each applet's own
  budget (`budget.day_usd`/`month_usd`, default $1 a day and $10 a month, see applet-packages.md): a paid call must fit
  both, and this phone budget is checked first, so at the defaults it is the one you reach first. Each call holds a little of the budget until its real cost is known. Over a
  limit: 429 `rate_limited` or `spend_cap`. Size anything frequent from these values: at the default budget, a paid
  photo every 10 s would last about 2.5 days on Jev. Skip unchanged pictures (watches do it with `local`) and check on
  events instead of a timer.
- **Shared by all of Bridgit** (`shared` in `GET /v1/limits`): a daily and a monthly total across every caller, and a
  pool for unverified phones. Any of them can run out before your own budget does.
- **Watches:** Seconds between readings: 30-3600 (default 60); down to 10 only for a camera tally with local.change_check enabled. Set max_paid_per_hour to bound optional model calls. The phone also caps paid checks across all its
  watches at `watch_paid_checks_per_hour` (default 240, 60 unverified). Budget arithmetic: Clef photos an hour around
  the clock ≈ `model_usd_per_month` ÷ ($0.000105 × 720); at the default budget that is about 20 an hour for all the
  phone's agents together, so give each always-on watch a `max_paid_per_hour` well under it. Checks answered on the
  phone (unchanged picture, `model: none`) are free and don't count.
- **Clef's free allowance:** about 970 screenshots or 220 ten-second clips a day for all of Bridgit. Past that,
  calls fail with 429 `clef_quota` until the next day unless the team has Cloudflare's paid plan on.
- **Time:** Jev gives up after 10 s, Clef after 30 s (both come back as an error, and no action runs). Typical
  answers take 0.2 to 0.7 s; a 20 s 720p video took about 4 s.

## App patterns

- **Don't interrupt.** Before a card, capture the screen and ask Clef whether the owner is on a call, paying, or
  signing. Hold the card on a confident yes.
- **Sound alerts.** A sound watch (`POST /v1/watches`, `"data": ["sound"]`) asks Clef about each loud sound on the
  phone, with you offline: smoke alarm, glass breaking, a baby crying, the doorbell, or nothing. Repeats of the same
  sound within `sound.reuse_s` reuse the last answer for free; `tally` counts episodes. Speak or show a card only on a
  confident hit. (`sound.heard` still gives you the raw loud sound and its clip, if you'd rather decide yourself.)
- **Camera watch.** `POST /v1/watches` with `"data": ["camera"]` and a yes/no ("is a package at the door?"): the phone
  takes a 640 px photo each reading and asks Clef only when the picture changed (`local` sets the free change check,
  `max_paid_per_hour` caps the bill, `usage` shows free vs paid). `"model": "none"` just calls you back when the
  picture changes, free. On a yes, record a 10 s clip and ask Clef to confirm before telling the owner.
- **Gallery sorting.** For each new photo in `GET /v1/gallery/recent`: receipt, document, screenshot, or personal photo? Offer to file it.
- **Sensor watch.** "Call me back when the owner starts cycling": `POST /v1/watches` with location and motion, Jev.
- **Triage text.** Bug reports, messages, notifications: crash, wrong result, or request? How likely does it affect
  others? Jev, through `/v1/judgment`, with a `speak` action for the urgent ones.

## Errors

| Error | Means | Do |
|---|---|---|
| 400 `invalid_parameter` | Bad state, questions, actions, or too many media items | Fix the request; nothing was billed |
| 413 `payload_too_large` | Body or a media item too big | Shrink, trim or send fewer |
| 413 `image_too_large` | A Jev image over 300 KB | Send a smaller photo (`size: agent`) |
| 422 `jev_rejected` / `clef_rejected` | The model refused the input (its words are included) | Usually the questions' shape, or a clip too long |
| 429 `rate_limited` / `spend_cap` / `clef_quota` | Over a limit above | Slow down or try tomorrow; don't retry in a loop |
| 502, 503, 504 | The model is down, busy or slow | Retry once after a few seconds; nothing ran |

Model details: [Jev](https://docs.typesafe.ai) and Cloudflare's
[Clef-omni](https://developers.cloudflare.com/workers-ai/models/clef-omni/). The full request and reply for
`/v1/judgment` and `/v1/watches` are in the [guide](https://bridgit.cc/learn/guide.md); an overview for people is at [https://bridgit.cc/decisions/](https://bridgit.cc/decisions/).
