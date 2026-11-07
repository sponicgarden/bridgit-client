# Manage a library applet

Before proposing an installed customization or publishing a template, run the [GLM practice review](https://bridgit.cc/learn/builder/practice-review.md) on the candidate and test the suggestions you adopt.


These routes describe the independent-library branch. Check the running server's
schema before assuming this revision is deployed. Write the package offline;
registration and publishing are separate from authoring.

Registry requests use https://bridgit-feedback.bridgit-app.workers.dev with
the prefix `/v1/templates`. For a new agent session, supply a delegated library
key from the owner's secret manager as `Authorization: Bearer <library-key>`.
Use GET `/schema` to discover support and GET `/owned` to find existing work.
Keep credentials out of package files, journals and chat transcripts. Device
installation and editing use the target device's own `/v1/applets` API and
device-agent grants. Signed devices can also read eligible registry revisions.

## Give a session library access

The owner can open [Agent library access](https://bridgit.cc/templates/#library-keys),
enter an agent name, choose a role, select **Issue key**, and copy the result
to the agent’s secret manager. The same controls support role changes, rotation
and revocation. The HTTP operations below use the owner’s verified sign-in. Choose the
role by what the session should accomplish across this owner's library.

| Role | Operations |
| --- | --- |
| `reader` | Read authorized editions, assets, catalog, visibility and history. Validate a configuration. |
| `editor` | Reader operations plus create, fork, revise and discard a latest draft. |
| `publisher` | Editor operations plus publish, deprecate and change visibility. |

1. Generate and save a UUID v4. POST `{"id":"<uuid>","name":"Applet author","role":"editor"}`
   to `/agent-keys` using the owner session. Store the returned `token` in the
   secret manager. Only issue and rotation return the secret. If the response is
   lost, GET `/agent-keys/{id}` with the saved ID and rotate the key.
2. GET `/agent-keys?limit=50` to list active credentials. Pass the returned
   `next` as `after`. GET `/agent-keys/{id}` for the current `generation`.
   POST `{"name":"Release publisher","role":"publisher","expected_generation":N}`
   to the same path to change an existing grant. Rotation uses POST
   `/agent-keys/{id}/rotate` with `{"expected_generation":N}`. A conflict
   requires a fresh read before another change.
3. DELETE `/agent-keys/{id}` to revoke it. GET `/agent-keys/{id}/activity?limit=50`
   to see its committed library writes, including after revocation. Pass `next`
   as `before` for older events. A library credential cannot manage credentials,
   access instance configurations or grant itself another role.

## Author and release an edition

1. Author one JSON definition. Use `runtime.adapter: "bridgit.package.v1"` for
   a portable package. Examples using `android.package.v1` use its legacy name.
   Download and unzip the [offline kit](https://bridgit.cc/applets/authoring/bridgit-applet-authoring-v1.zip).
   Run `java -jar validate-applet.jar path/to/applet.json` with Java 17 or newer. Read the returned
   `not_checked` list. For a device-specific dry run, POST `{"package": ...}`
   to the device's `/v1/applets/validate`. Neither check installs anything.
2. Before creating or forking, save a fresh UUID, the request path and the exact
   JSON bytes in your session journal. Send the UUID as the `Idempotency-Key`
   header. After a timeout or session restart, repeat the same request with the
   same owner credentials, key and bytes. HTTP 201 means creation succeeded.
   HTTP 200 on replay returns the original edition key and revision 1 in its
   current state. HTTP 409 with `idempotency_conflict` means that operation key
   was used for another request. HTTP 410 means the created draft was deleted.

   POST `{"definition": ...}` to the registry prefix. Save the returned
   `key` and `revision`. The new revision is a private draft. The registry
   key identifies the publication; the definition's id is the runtime package id.
3. POST `{}` to `/{key}/{revision}/publish`. This makes the revision
   immutable and installable for its authorized readers. A new entry is private.
   To list it, GET `/{key}/visibility`, then POST to that same path with
   `{"visibility":"public","expected_generation":N}` using the returned generation.
   Use `unlisted` for link access without discovery, or `private` to restrict
   subsequent reads. Sharing an edition exposes its current published revision
   and releases published while sharing is enabled. Earlier private revisions
   stay owner-only. Returning the edition to private restricts remote reads of
   every revision. Devices retain their downloaded copies.
4. GET `/catalog?kind=applet&q=lamp&limit=50` for installable applet packages.
   Add `scope=owned` to search the signed-in owner's published editions, including
   private forks. Rows point at the published revision even when a newer draft
   exists. The default scope is `public`.
   Use `kind=interaction` for interaction templates or omit `kind` to browse both.
   Each listing includes its kind. Pass the returned `next` as `after`
   until null. GET `/{key}/{revision}` for exact bytes of the asset text inside
   the JSON definition. The catalog is metadata only. To install a selection,
   POST `{"template":"KEY","revision":N}` to the *device's*
   `/v1/applets/install`. Report pending approval as pending; verify the result
   via `/v1/applets/installed` and the installed instance's data endpoint.
5. To create a separate private edition, POST `{}` to the source revision's
   `/{key}/{revision}/fork`. Save the *new* key and revision and keep the returned
   ancestry. Editing the source's instance settings does not require a fork.
6. If a new session needs to find your existing editions, GET `/owned?limit=50`
   and pass `next` as `after` to continue. Each entry includes the latest
   private or published revision, visibility, current published revision and
   any fork parent. The owner's verified session or a delegated reader, editor or publisher key can list these editions. Linked devices use the published-only catalog.

   To recover edition history in a new session, GET `/{key}/revisions?limit=50`.
   Results contain revision, semantic version, state and creation time, newest
   first. Pass the returned integer `next` as `before` until it is null. Fetch
   the exact revision you need separately. The signed-in owner and their delegated library keys can see drafts.
   Other readers see only revisions they can open. An empty list can mean that
   the caller has no access. Revision numbers are positive 32-bit integers.

   To revise an owned edition, POST `{"definition": ...}` to
   `/{key}/{current_revision}/revisions`. Keep the id and kind, advance the
   semantic version, and set `migration.from` to the prior version. Publish the
   returned revision. On a conflict reread current state before reapplying edits.
   Installed devices follow their own update policy and approval process.
7. POST `{}` to `/{key}/{revision}/deprecate` to retire a published revision.
   For a shared edition the listing falls back to the latest previously shared
   release still published, or disappears if there is none. Shared pins remain
   readable by exact revision while the edition is public or unlisted.
   DELETE `/{key}/{revision}` removes an owned latest draft once its fork and configuration references are gone. Use the device's
   installed-instance endpoints for pause, update or removal; removing an instance
   and withdrawing a library entry are separate operations.

A registry read or a successful dry run does not prove that a device granted a
permission, can run a selected model, or has activated an update.

To retrieve an individual package asset, send an authenticated GET to
`/{key}/{revision}/assets/{name}`. The default reply is its JSON descriptor.
Send `Accept: application/octet-stream` to download the exact file bytes of any supported asset, including UTF-8 text and PNGs. Verify the SHA-256 against the descriptor from the same revision before caching the file. Treat downloaded HTML and SVG as untrusted package data.
For a PNG, send `Accept: image/png` to get the original bytes. Verify those
bytes against the descriptor's SHA-256 before caching them. Use the same
account or signed-device authority as the enclosing revision. Use the revision key,
revision number, and asset name as the durable reference. The registry manages
deduplication, failed uploads, and cleanup of unused objects. Deleting a draft
leaves assets used by other revisions intact.

For a signed-in owner, GET `/account` returns `library_account`, a stable,
opaque identifier for isolating this owner's offline library. A client can
bind that value to the verified session in its secure credential store. The
identifier alone grants no access. Signed-device credentials cannot obtain
the owner's local-library namespace.

## Attach a large JPEG to an exact revision

1. Keep the JPEG beside the source manifest. In the source package's `assets` map use, for example, `"guide.jpg":{"media_type":"image/jpeg","storage":"revision","path":"media/guide.jpg"}`. Run `pack-applet.py` offline. The packed descriptor contains `storage`, `media_type`, `size` and the lowercase hex `sha256`. Keep the source file with the manifest for offline export.
2. When publishing, send `PUT /v1/templates/media/{sha256}` with `Content-Type: image/jpeg`, the raw file bytes, and an owner, editor or publisher credential. The response is the descriptor. Compare it with the packed descriptor. A retry with identical bytes is safe. JPEGs are limited to 4 MiB and 4096 pixels per edge. Detached media uses a 1 GiB allowance per owner including staged files. Identical content within one owner counts once.
3. Create or revise the package with the descriptor. The registry checks that the exact file was uploaded to this owner before attaching it. An unattached upload can expire after 24 hours. Upload again if the server returns `missing_asset`. Revisions retain their files, including when a newer revision replaces them.
4. Read the exact revision to get the descriptor. Download the asset using its revision-bound asset endpoint and `Accept: application/octet-stream`. Verify size and digest before use. Forking through the registry copies media into the new owner's storage. The original owner's visibility controls access to the source revision.

Use detached media only with a supporting host. The Android and Apple implementations list image names and sizes at review and verify each download before activating the edition. On Android, expand the installed applet and select the image name. On Apple, open Package images in the installed edition. An unsuccessful download or update preserves the installed edition. Choose inline assets when targeting older clients that predate detached-media support.
