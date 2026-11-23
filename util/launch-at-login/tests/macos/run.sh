#!/usr/bin/env bash
set -euo pipefail
directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fixture_directory="$(mktemp -d "${TMPDIR:-/tmp}/keyguard-login-service.XXXXXX")"
trap 'rm -rf "$fixture_directory"' EXIT
xcrun clang -fobjc-arc -Wall -Wextra -Werror \
  -framework Foundation -framework ServiceManagement \
  "$directory/service_test.m" -o "$fixture_directory/service-test"
"$fixture_directory/service-test"
