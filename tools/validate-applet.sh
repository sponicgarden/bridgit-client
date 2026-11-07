#!/bin/bash
# Uses the runtime's validators offline. Stdout contains the result for this invocation.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
if [[ $# -lt 1 || $# -gt 2 || ! -f "$1" ]]; then
  echo 'Usage: tools/validate-applet.sh applet.json [output-directory]' >&2
  exit 64
fi
package_file=$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$1")
output_dir=${2:-"$repo/app/build/applet-authoring"}
mkdir -p "$output_dir"
output_dir=$(cd "$output_dir" && pwd)
# A failed build or another model's validation must never supply this invocation's result.
run_dir=$(mktemp -d "$output_dir/.validation.XXXXXXXX")
trap 'rm -rf "$run_dir"' EXIT
cd "$repo"
status=0
./gradlew :app:testPrivateDebugUnitTest --rerun --offline \
  --tests cc.bridgit.app.applets.AppletAuthoringValidationTest.validateAndExportContract \
  "-PbridgitAppletPackageFile=$package_file" "-PbridgitAppletOutputDir=$run_dir" >&2 || status=$?
if [[ ! -f "$run_dir/validation.json" ]]; then
  echo 'Build failed before the package validator produced a result.' >&2
  exit 1
fi
cat "$run_dir/validation.json"
printf '\n'
for file in flow-contract.json services.json validation.json; do
  if [[ -f "$run_dir/$file" ]]; then mv -f "$run_dir/$file" "$output_dir/$file"; fi
done
exit "$status"
