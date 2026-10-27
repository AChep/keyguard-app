#!/usr/bin/env bash

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BINARY_NAME="keyguard-android-gpg-agent"
GRADLE_TASK=":androidGpgAgent:compileAndroidGpgAgentAll"

termux_steps() {
    cat <<EOF
  pkg install gnupg
  eval "\$(${BINARY_NAME})"

Then import the public key exported from Keyguard and verify inside Termux:
  gpg --import ~/storage/downloads/gpg_*.public.asc
  gpg --list-secret-keys --with-keygrip
EOF
}

source "${MODULE_DIR}/../commonAgent/android-bridge/install-adb.sh"
