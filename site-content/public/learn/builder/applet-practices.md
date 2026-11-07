# Applet practices: contract and enforcement

The single source is `site-content/content/builder/practices.json`. Stable IDs also
appear in observations, review findings, exceptions and validation diagnostics.
The generated catalog at `/learn/builder/practices.json` includes dimensions,
applicability, rationale, acceptance criteria, shared defaults and required checks.
Policy evidence is distinct from implementation, flow tests and device tests.

Start with the [agent workflow and worked example](https://bridgit.cc/learn/builder/practice-review.md#agent-workflow). During planning, combine `by_task.all` with relevant task tags. Use the candidate validator’s complete applicability report to select checks once a package exists. Catalogue `acceptance` entries describe observable test scenarios. Record the inputs, observed result and artifact/runtime identity when exercising them. Keep checks unverified until exercised.

Every creation and modification must consider whether its lesson belongs here.
Use an existing ID where possible. Record `ui.practices.review` with `decision`
(`no-new-practice`, `add` or `change`), a concrete `reason` and `practice_ids`.
For additions/changes, include `proposal`. Never put private instance data here.

`ui.practices` uses `contract: "bridgit-practices/1"` and `catalog: "1.0.0"`.
Its optional `exceptions` list contains `id`, `practice`, `target` (JSON pointer),
`reason`, `alternative`, `acceptance` (test descriptions) and `review_on`.
Exceptions cannot waive authentication, isolation, owner revocation or initial
permission authority. They are proposed metadata, not self-issued approval.
The normal authenticated publication or installation approval accepts the exact
package digest. Authorized controlling agents can use the existing delegated
approval API. No new approval queue or arbitrary phone tap is introduced.

The evaluator derives applicability from package content. It reports structural
checks separately from runtime defaults and behavior that still needs a test.
Agent/model evidence never becomes a trusted test pass. The report binds the
catalog and candidate digests. Validation returns the report; creation, revision,
publish, agent installation and update gates require the catalog review record.
Re-evaluate at approval so stale metadata cannot bypass the gate.

Existing applets keep running. Parsing old releases does not silently rewrite
or disable them. An edit assesses the whole package; unchanged failures remain
explicit legacy debt. A new failure or a changed surface cannot inherit that debt.
Exceptions are valid only while their targeted content remains identical. A
changed target needs a new exception ID and a new review. Unknown catalog or
contract versions are rejected, not guessed. Personal upgrades keep ancestry,
settings, stable IDs and intentional removals through the existing merge guards.

The shared web UI supplies keyboard table sorting, image viewing, the capture
icon and a truthful progress renderer. Templates declare source values and state
mappings; they do not calculate progress from presentation guesses. Custom
controls keep their existing behavior and need applicable behavioral checks.

Required checks cover contract parity, direct-route bypass attempts, stale
exceptions, incorrect evidence, custom HTML, typed sorting, focus restoration,
paged media, mixed processing outcomes, and legacy edits. Screen-reader, visual
and device tests remain named requirements; a static validator cannot prove them.

## Package metadata example

```json
{"ui":{"practices":{"contract":"bridgit-practices/1","catalog":"1.0.0","review":{"decision":"no-new-practice","reason":"The typed table and source photos use the shared runtime controls.","practice_ids":["TABLE-001","MEDIA-001","GOV-001"]}}}}
```

Merge `practices` into the existing `ui` object; retain its card and portal.
`review` is a required author statement, not a trusted test result. The optional
exceptions remain proposals until the exact package receives normal publication
or installation approval. Existing immutable releases remain readable, including
historical upgrades without this metadata. Agent-authored changes must adopt it.

Installing or taking an unchanged, authenticated historical registry revision is not new authoring. It keeps its pin and origin. Inline/URL-authored definitions and agent edits require the review record. Once an instance adopts the contract, a later update cannot remove it.

## Managed HTML defaults

Use `<body data-bridgit-ui>` (or a marked container) in new HTML cards. The shared runtime enhances its bare images and semantic capture controls, preserving existing buttons/links. It exposes `BridgitUI.openMedia({items,index,loadMore,resolve})` and `BridgitUI.renderProgress(element,{taken,processed,failed,active,queued,total})`. A custom HTML card without a managed container needs a UI-001 exception targeting its asset and equivalent behavioral checks. Legacy custom HTML keeps its handlers until explicitly adopted.
