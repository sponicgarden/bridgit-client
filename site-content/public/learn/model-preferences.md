# Applet model preferences

Preferences use stable **applet package IDs**, not installation IDs or display names. A renamed applet keeps its preferences.

Resolution is live on each call: **account override → applet default → shared task default**. Changing an applet default changes every instance of that applet in every account that inherits it. Explicit account overrides remain unchanged. Clearing an override restores inheritance immediately; defaults are never copied into instances.

Read `GET /v1/services/models` for tasks and your account's explicit overrides, then `GET /v1/services/models/chef-helper/speech_speak` for effective models, available choices and `source` (`account_override`, `applet_default`, `shared_default`). No paid call is made.

An agent with the existing owner.configuration grant can set an account override through `POST /v1/owner/configure`:

```json
{"action":"service.model.set","options":{"applet_id":"chef-helper","task_key":"speech_speak","model_id":"speech-aura-1"}}
```

Use `"model_id":null` in the same action to clear it. The account override covers every installation of that applet in this account. It cannot change other accounts or the shared defaults. The equivalent configuration action is available through the authenticated unattended configuration API.

The model registry's Applet ID column and Applet defaults editor register shared applet preferences. They use the existing administrator-authorized model-task APIs. Registering an applet with **Use shared default** preserves live inheritance.

Image generation and speech look up these preferences for each call. Image calls can specify a registered applet image `task_key`; the default is `image_generation`. Model changes grant no capabilities, bypass no cloud-speech consent and do not change spending limits. Images support Microsoft Azure GPT Image 2.5 Sunburst and OpenRouter FLUX 3 Image Klein; the shared 40 images per account per month still applies. Wordmarks require Azure transparency. Disabled tasks and incompatible/deprecated models stop the call; they do not silently choose another model.
