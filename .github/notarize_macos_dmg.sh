#!/usr/bin/env bash
# Notarize the already signed DMG without running the build or signing it again.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <dmg-directory> <log-directory>" >&2
  exit 1
fi

: "${NOTARIZATION_APPLE_ID:?Missing Apple ID}"
: "${NOTARIZATION_PASSWD:?Missing app-specific password}"
: "${NOTARIZATION_ASC_PROVIDER:?Missing Apple developer team ID}"

shopt -s nullglob
dmgs=("$1"/*.dmg)
if [[ ${#dmgs[@]} -ne 1 || ! -f "${dmgs[0]}" ]]; then
  echo "Expected exactly one DMG in $1" >&2
  exit 1
fi
dmg="${dmgs[0]}"
log_dir="$2"
mkdir -p "$log_dir"

credentials=(
  --apple-id "$NOTARIZATION_APPLE_ID"
  --password "$NOTARIZATION_PASSWD"
  --team-id "$NOTARIZATION_ASC_PROVIDER"
)

# Save the submission ID before waiting, including when waiting times out.
submit_status=0
xcrun notarytool submit "$dmg" "${credentials[@]}" --output-format json \
  > "$log_dir/submission.json" 2> "$log_dir/submit-stderr.txt" || submit_status=$?
cat "$log_dir/submission.json"
cat "$log_dir/submit-stderr.txt" >&2
if [[ "$submit_status" -ne 0 ]]; then
  exit "$submit_status"
fi
submission_id=$(jq -er '.id | select(type == "string" and length > 0)' "$log_dir/submission.json")

wait_status=0
xcrun notarytool wait "$submission_id" "${credentials[@]}" --timeout 30m --output-format json \
  > "$log_dir/status.json" 2> "$log_dir/wait-stderr.txt" || wait_status=$?
cat "$log_dir/status.json"
cat "$log_dir/wait-stderr.txt" >&2

# Fetch Apple's diagnostics even for rejected submissions. A network failure
# fetching diagnostics must not hide the submission's actual result.
xcrun notarytool log "$submission_id" "${credentials[@]}" "$log_dir/notarization.json" \
  2> "$log_dir/log-stderr.txt" || echo "::warning::Could not retrieve Apple's notarization log."
cat "$log_dir/log-stderr.txt" >&2
if [[ "$wait_status" -ne 0 ]]; then
  exit "$wait_status"
fi
if ! jq -e '.status == "Accepted"' "$log_dir/status.json" > /dev/null; then
  echo "::error::Notarization was not accepted. See the notarization logs."
  exit 1
fi

# Ticket delivery uses a separate service and can fail after acceptance.
# Retry stapling the same DMG without resubmitting it to the notary service.
delay=15
for attempt in 1 2 3 4 5; do
  if xcrun stapler staple "$dmg" 2>&1 | tee "$log_dir/staple-$attempt.txt"; then
    break
  else
    staple_status=$?
  fi
  if [[ "$attempt" -eq 5 ]]; then
    exit "$staple_status"
  fi
  echo "Stapling failed; retrying in ${delay}s ($attempt/5)."
  sleep "$delay"
  delay=$((delay * 2))
done

xcrun stapler validate "$dmg" 2>&1 | tee "$log_dir/validate.txt"
