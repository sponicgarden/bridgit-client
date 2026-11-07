# Review a candidate before publishing or customizing

## Agent workflow

| When | Action | Result location |
| --- | --- | --- |
| Before editing | Read the installed development snapshot and preserve personal requirements. For planning, combine catalogue `by_task.all` with tags for every relevant feature. | Working notes |
| Candidate ready | Choose `no-new-practice`, `add` or `change`, explain the lesson and name affected practice IDs. Include `proposal` for additions or changes. | Package `ui.practices.review` |
| Before proposing or publishing | Sanitize the candidate, run the GLM review below, assess suggestions and exercise accepted fixes. Re-review every changed candidate. | Request `practice_review` with returned digest and disposition, where required by the operation |
| Before approval | Validate the final candidate. Follow each applicable result's named checks and acceptance criteria. Reassess changed exception targets. | Validation report and change evidence |
| After applying | Exercise the agreed example and report observed outcomes plus remaining unverified checks. | Owner-facing result |

Task tags help planning. Validation derives applicability from the complete package and code. For a photo applet with a table and image viewer, start with `all`, `photo`, `tables`, `media` and `ui`, adding `data`, `async`, `editable` or `upgrade` when relevant. Include every applicable result from validation even when its task tag was absent from the planning list. Shared defaults still require the named behavioral checks.

### Worked example — modify a photo table

This illustrative walkthrough adds date sorting to an installed photo table whose owner wants to retain a custom capture button. Sample outcomes below demonstrate reporting format and require actual test evidence before reuse.

1. Read the development snapshot and current revision. Preserve the custom button, personal settings and stable table IDs. Inspect existing exceptions and their exact targets.
2. Add sorting using typed capture timestamps. Keep existing handlers. If adopting managed UI, test its interaction with those handlers. A custom HTML asset outside a managed container requires a scoped UI-001 exception with rationale, alternative and acceptance checks. A changed exception target needs a new exception ID and review.
3. Merge this metadata into the existing `ui` object, retaining card and portal fields. Select further IDs from the actual validation report.

```json
{"practices":{"contract":"bridgit-practices/1","catalog":"1.0.0","review":{"decision":"no-new-practice","reason":"The photo table adds typed date sorting and retains the owner's custom capture handler. Existing table, media and control practices cover these surfaces.","practice_ids":["TABLE-001","TABLE-002","MEDIA-001","MEDIA-003","UI-001","GOV-001"]}}}
```

4. Export and sanitize the candidate definition, then run the GLM review with workflow `customize`. Apply suitable suggestions, regenerate changed asset hashes and re-review. On the update request, include the separate review envelope below, replacing the digest with the exact returned value. Keep `change.expected_revision`, intent, acceptance and evidence as required by the [modification guide](https://bridgit.cc/learn/builder/modifying-applets.md).

```json
{"practice_review":{"model":"z-ai/glm-5.3-flash","source_sha256":"<digest returned by the final candidate review>","disposition":"Adopted typed capture-time sorting. Retained the owner's custom capture handler and tested its single-submission behavior."}}
```

If cloud review is unavailable, use the documented unavailable status and reason below and disclose it. Validate the final candidate and run its applicable checks before submitting through the normal approval flow. Preserve any unverified outcomes in the report.

5. Example report after actual browser tests could read “Typed dates sort in both directions across two pages. Capture double-click submits once. Screen-reader and physical-device capture checks remain unverified.” Attach the tested artifact/runtime identity and test evidence. After approval, confirm the installed revision and repeat the agreed example on the target runtime.

Run the review as soon as you have the proposed package, before the publish request or the installed-app update proposal. For an installed copy, preserve the current revision and its personal requirements. Export only the candidate definition. Remove private data, credentials and private defaults from any source sent to the model. Never send an installed development snapshot. Use the owner's existing cloud-processing authorization. When cloud processing is unavailable, report the review as unavailable.

With a library editor credential, POST to the registry at `/v1/templates/practice-review` with `{"workflow":"customize","candidate":<sanitized package>,"allow_cloud":true}`. For a sanitized registry draft, POST `{"review_first":true,"allow_cloud":true}` to its normal publish URL. This returns findings while keeping the draft editable. After testing the accepted fixes, publish the reviewed revision normally. Include a short sanitized `intent` (CLI: `--intent`) when a deliberate product choice affects the review. Each library owner can request up to ten hosted reviews per UTC day.

For builders using their own OpenRouter credential:

1. Download [review-applet.py](https://bridgit.cc/learn/builder/review-applet.py). From a repository checkout use the copy in `site-content/public/learn/builder/`. Use the builder's configured OpenRouter key in `OPENROUTER_API_KEY`.
2. Run `python3 review-applet.py candidate.json --workflow customize` or use `--workflow publish`. The reviewer uses GLM 5.3 Flash, the shared practice catalog, and the exact candidate. Its JSON response includes the candidate digest, practice IDs, feedback, suggested edits and verification steps.
3. Compare each suggestion with the owner's intent and current API. Apply suitable edits to the source asset, regenerate the package and its hashes, and exercise the named behavior. Explain any declined suggestions. Re-review a changed candidate. Keep the review and disposition with the change, then use the ordinary owner approval or publication process. An empty findings list is a completed review; an unavailable model is not.

When an installed update or a library-agent publish returns `needs_practice_review`, keep the candidate editable and run the review. On retry include `practice_review: {model,source_sha256,disposition}` using the returned digest and a brief account of feedback adopted or declined. If review is unavailable, explicitly report `practice_review: {status:"unavailable",reason}` and disclose it to the owner. These fields report the agent’s work. The normal validation and owner approval still apply.

## Required practice contract

Before creating or changing an applet, read [the practice contract](https://bridgit.cc/learn/builder/applet-practices.md) and [catalog](https://bridgit.cc/learn/builder/practices.json). Record the catalog review in `ui.practices`. The validator reports applicable practices, defaults and checks. Run the named behavioral checks; unverified is not passed. Use a justified exception only for its exact target. Reassess exceptions when the target changes.
