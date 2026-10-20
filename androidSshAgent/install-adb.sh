#!/usr/bin/env bash

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BINARY_NAME="keyguard-android-ssh-agent"
GRADLE_TASK=":androidSshAgent:compileAndroidSshAgentAll"

termux_steps() {
    cat <<EOF
  pkg install openssh
  if [ -x "\$PREFIX/bin/${BINARY_NAME}" ]; then
    eval "\$("\$PREFIX/bin/${BINARY_NAME}" --ensure -a "\$PREFIX/tmp/keyguard-ssh-agent.sock")"
  fi

Then verify inside Termux:
  ssh-add -L
EOF
}

source "${MODULE_DIR}/../commonAgent/android-bridge/install-adb.sh"
