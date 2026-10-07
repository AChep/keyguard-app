#!/usr/bin/env bash
set -euo pipefail

manifests=()
case "$PREFETCH_PROFILE" in
  desktop|android|apple)
    # The root workspace and native crypto hold every shipped native graph.
    manifests+=(
      Cargo.toml
      util/crypto/rust/Cargo.toml
    )
    ;;
  "") ;;
  *)
    echo "Unknown native prefetch profile: $PREFETCH_PROFILE" >&2
    exit 1
    ;;
esac

while IFS= read -r manifest; do
  manifest="${manifest%$'\r'}"
  [[ -z "$manifest" ]] && continue
  manifests+=("$manifest")
done <<< "$PREFETCH_MANIFESTS"

if [[ ${#manifests[@]} -eq 0 ]]; then
  echo "Specify a native prefetch profile or explicit manifests." >&2
  exit 1
fi

# Validate the complete list before fetching; a stale profile must fail immediately.
for manifest in "${manifests[@]}"; do
  if [[ ! -f "$manifest" ]]; then
    echo "Missing native Cargo manifest: $manifest" >&2
    exit 1
  fi
done

printf '%s\n' "${manifests[@]}" | sort -u | while IFS= read -r manifest; do
  cargo fetch --manifest-path "$manifest" --locked
done
