#!/usr/bin/env bash
set -euo pipefail

properties="${CI_GRADLE_PROPERTIES:-}"
if [[ -z "${properties//[[:space:]]/}" ]]; then
  exit 0
fi

gradle_home="${GRADLE_USER_HOME:-${HOME}/.gradle}"
mkdir -p "$gradle_home"

# The last value for a key wins. Two newlines also terminate a continued
# property at EOF, so it cannot swallow the first override.
printf '\n\n%s\n' "$properties" >> "$gradle_home/gradle.properties"
