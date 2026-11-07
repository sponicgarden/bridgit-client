# Applet interaction standard

Owner requirement, 6 November 2026. A person must be able to tell what will happen,
what is happening, what was captured, and whether an action succeeded.

## Person-started camera capture

- Show a live viewfinder before taking a photo or recording video. Offer a visible
  front/rear camera switch before capture. Do not start countdowns before the
  viewfinder is ready.
- Show a clear shutter/Record control, countdown where applicable, and an elapsed
  recording timer with Stop. Show the captured result before accepting it.
- Offer Use, Retake and Delete. Video playback is optional and must not autoplay.
  Accepted video can be reopened for Play/Pause or deletion. Deleting a selected
  capture must also clear its selection and dependent ready state.
- Closing the camera, cancellation, revocation or loss of foreground must stop
  capture. If the viewfinder cannot be shown, fail without a hidden fallback.
- Automatic/background monitoring is explicitly exempt. Do not turn a watch or
  scheduled observation into an unexpected camera takeover.

Enforcement is native: `Req.invocation` is set by the dispatch layer; card,
portal and MCP-App snapshot/video requests go through `VisibleCapture` and
`CaptureReviewActivity`. `viewfinder:false` cannot opt out. Button/portal-started
workflow photo steps use the same visible flow; automatic sampling is unchanged.
Legacy interactive arm/pose/LAN snapshot paths without a supported viewfinder
fail with `viewfinder_required`. The existing table camera already has a preview.

## Buttons and background work

Give immediate pressed/focus feedback. A running action shows a working state,
blocks duplicate taps, and has a Stop or Cancel control when appropriate. Success,
error, cancellation and empty states must have distinct, actionable text.

Keep progress visible while the person scrolls. Use real stages and elapsed time.
Use an indeterminate indicator when a percentage cannot be measured; do not invent
percentages. Long native card actions also receive a runtime elapsed-work indicator.
Do not expose internal implementation counts such as extracted video frames as
completion feedback. Use “Video ready” or “Photo ready” and show the actual result.

Expose transient UI state through `Bridgit.portal.setState` so an authorized agent
can inspect the current phase. Durable records belong in declared applet tables.
Clean up superseded capture IDs after a successful replacement; retain the previous
selection if the new capture is cancelled.

## APIs

`POST /v1/camera/snapshot` and `/v1/camera/video` accept `viewfinder:true` for an
explicit API-initiated visible flow and `countdown_seconds` (0–10). Card provenance
always enforces visible capture. Camera selection remains the `camera` field; the
response reports the actual lens selected in the viewfinder.

`extract_frames` is internal processing. For accepted interactive videos its JSON
reply includes `video_capture_id`, which follows existing capture-memory retention.
`POST /v1/camera/video/review {capture_id}` reopens it without using the camera.
`GET /v1/captures/{id}?max_edge_px=240` reads a bounded image preview; existing
`DELETE /v1/captures/{id}` removes the caller-owned capture. Grants, owner capture
policy, paused/removed applet checks, microphone consent and existing budget
controls continue to apply. The owner can configure budgets and capture retention
through their existing authenticated APIs; no UI-only preference is introduced.

Checks cover native provenance (including an attempted opt-out), the automatic
monitoring exception, card pending/success/error/Stop states, replacement cleanup,
preview/delete/playback requests and both story/model modes. Device verification
must inspect the actual viewfinder, flip, recording clock, review, playback and
deletion, not only a successful HTTP reply.
