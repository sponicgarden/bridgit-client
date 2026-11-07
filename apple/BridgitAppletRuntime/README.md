# Apple applet execution core

Swift package using Foundation and CryptoKit targeting iOS 17 and macOS 14. `swift test` exercises
result references, conditional steps, stop, and refusal of unavailable host steps.
Pass the hydrated registry response's `definition` (or the bytes of an offline
`applet.json`) to `AppletPackage(data:)`. It checks the format, adapter, asset
checksums and flow declarations. After validating the owner's configuration,
call `steps(for:configuration:)` with a named flow or `main` for a single workflow.
Pass those steps to the runtime with the instance's authorized platform host.

The tests load the Terra, Sol and Luna authoring trials from the repository.
Two workflows run against deterministic test services. The watch package test
checks its HTML asset and rejects tampered text.

This is a flow execution core, not a complete local applet host. Production use
requires package/schema validation, permission-aware platform drivers, persistent
instance storage, scheduler lifecycle, UI bridges, provider bindings and budgets.
Only already-validated linear flows should enter `FlowRuntime.run`. The host must
honor cancellation, recheck authority, implement idempotency using the supplied
action context, and never treat declared capabilities as grants. Explicit retries
can repeat external effects unless the host implements idempotency.

Full Android/Apple condition parity, full schema validation, persistent suspended flows,
and integration into the application targets remain release requirements. See
../../docs/applet-package-contract.md. No cross-platform execution release is
claimed by the existence or successful compilation of this package.

An installed button flow can request a front or rear photo with the reviewed
`camera.snapshot` permission. The iOS and Mac Catalyst viewfinder offers flip,
retake, use and cancel. Accepted captures stay in the run by default.
`keep:true` returns an instance-private JPEG reference that a typed photo column
can retain and display after relaunch. Each instance can keep up to 1,000 photos
and 500 MiB. A kept photo without a live row remains available for seven days
from capture. Cleanup runs on database open and writes. Removing an installation
deletes its images. A flow requiring an unavailable model or LAN camera fails
preflight before opening the camera.

Camera card buttons can save a reviewed JPEG directly into a declared photo
column. The Apple host reads the lens, size, destination table and fixed column
values from the installed package. Large and medium photos use a maximum long
edge of 2048 and 1280 pixels respectively. When the table declares a time
column named `taken_at`, the host fills it on acceptance. The photo and row
commit together after the host rechecks that the same revision and instance
are still installed. This path runs locally, including when offline.
