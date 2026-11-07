# Image generation

Discover `GET /v1/services` (MCP `services`). The `images` service uses **Microsoft Azure GPT Image 2.5 Sunburst** for generated images, including applet wordmarks. It uses the existing `judgment` capability, enabled and granted by the owner. A linked, verified account is required. Azure credentials remain on the server.

`GET /v1/images/usage` returns `limit`, `used`, `remaining` and `resets_at` without a paid call. Each Bridgit account has **40 images per calendar month, UTC**, shared across all phones, agents, applets and wordmarks. This is separate from decision-model allowances. Team accounts also have this image limit. Sponic is a separate service and is unchanged.

Send `POST /v1/images/generations` with an `Idempotency-Key` header (8–128 letters, digits, dash or underscore):

```json
{"prompt":"A friendly otter wearing a purple scarf","size":"1024x1024","quality":"medium","output_format":"png"}
```

One image per request. `prompt` takes 1–32000 characters. Sizes: `1024x1024`, `1536x1024`, `1024x1536`. Quality: `low`, `medium` (default), `high`. Formats: `png` (default), `jpeg`. Background: `auto` (default), `opaque`, `transparent` (PNG only). Images return as `data[0].b64_json` with model, format, usage, cost and quota. JPEG uses compression 80. The current Azure deployment does not support WebP.

Retry only with the same key and identical request. The backend replays completed results privately for 24 hours without another generation. `409 image_in_progress` means pending or unknown; do not submit with a new key. Expired results return `409 idempotency_expired` without regenerating. A definitive provider refusal gives back the image slot; timeouts and uncertain outcomes retain it. At 40: `429 image_quota_exceeded`, including the reset time.

Account service spending limits and applet budgets also apply. Read `GET /v1/services/budgets`; set or clear optional spending limits through the existing authenticated `service.budget.set` configuration action with service `images`. The fixed 40-image allowance cannot be raised through this action. There are no new owner-editable fields or credentials on the phone.

## FLUX 3 Image Klein

The `flux_images` service uses **FLUX 3 Image Klein through OpenRouter**. Send the same authenticated endpoint and a new idempotency key with `task_key:"flux_image_generation"`. Azure remains the shared default; wordmarks keep Azure transparency.

```json
{"task_key":"flux_image_generation","prompt":"An otter wearing a green scarf, editorial illustration","size":"1024x1024","output_format":"png"}
```

The same account allowance of **40 images per month** covers both providers. FLUX supports PNG/JPEG and the existing three sizes; use default `quality:"medium"` and `background:"auto"` or `"opaque"`. Transparent backgrounds and nonstandard quality are rejected before billing. Requests are pinned to Black Forest Labs on OpenRouter with no provider fallback. Reported cost is returned in `cost_usd`; without a provider cost the documented price is returned with `cost_estimated:true`. Approximate prices are $0.009 at 1K and $0.011 at 1.5K; spending limits also apply.

The registered model `openrouter-flux-3-image-klein` can be selected through the existing applet defaults/account override APIs. Read choices at `/v1/services/models`. Selecting FLUX grants no authority and does not raise quotas or budgets.
