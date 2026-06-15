#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BINARY_NAME="keyguard-android-ssh-agent"
BUILD_DIR="${SCRIPT_DIR}/build/bin"
ADB_BIN="${ADB_BIN:-adb}"
REMOTE_PATH="/sdcard/Download/${BINARY_NAME}"
SERIAL=""
TARGET=""

usage() {
    cat <<EOF
Install ${BINARY_NAME} onto a connected Android device using ADB.

Usage:
  ./androidSshAgent/install-adb.sh [options]

Options:
  -s, --serial <serial>           Target a specific adb device serial.
  -t, --target <rust-target>      Override target detection.
  -r, --remote-path <path>        Device destination path on shared storage.
                                  Default: ${REMOTE_PATH}
  -h, --help                      Show this help.

Supported targets:
  aarch64-linux-android
  armv7-linux-androideabi
  x86_64-linux-android

Notes:
  - The matching binary must already exist under:
      androidSshAgent/build/bin/<rust-target>/${BINARY_NAME}
  - This script is intended for Termux deployment, not direct adb-shell execution.
  - adb usually cannot write directly into Termux's private app directory,
    so the binary is pushed to shared storage first.
  - Finish installation from a Termux shell by moving the binary into \$PREFIX/bin.
EOF
}

info() {
    echo "INFO: $*"
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

adb_cmd() {
    if [ -n "${SERIAL}" ]; then
        "${ADB_BIN}" -s "${SERIAL}" "$@"
    else
        "${ADB_BIN}" "$@"
    fi
}

map_abi_to_target() {
    case "$1" in
        arm64-v8a)
            echo "aarch64-linux-android"
            ;;
        armeabi-v7a)
            echo "armv7-linux-androideabi"
            ;;
        x86_64)
            echo "x86_64-linux-android"
            ;;
        *)
            fail "Unsupported Android ABI: $1"
            ;;
    esac
}

detect_target() {
    local abi
    abi="$(adb_cmd shell getprop ro.product.cpu.abi | tr -d '\r')"
    if [ -n "${abi}" ]; then
        map_abi_to_target "${abi}"
        return
    fi

    local abi_list candidate
    abi_list="$(adb_cmd shell getprop ro.product.cpu.abilist | tr -d '\r')"
    IFS=',' read -r -a candidates <<< "${abi_list}"
    for candidate in "${candidates[@]}"; do
        candidate="${candidate#"${candidate%%[![:space:]]*}"}"
        candidate="${candidate%"${candidate##*[![:space:]]}"}"
        if [ -n "${candidate}" ]; then
            map_abi_to_target "${candidate}"
            return
        fi
    done

    fail "Could not detect a supported Android ABI from the connected device."
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        -s|--serial)
            [ "$#" -ge 2 ] || fail "Missing value for $1"
            SERIAL="$2"
            shift 2
            ;;
        -t|--target)
            [ "$#" -ge 2 ] || fail "Missing value for $1"
            TARGET="$2"
            shift 2
            ;;
        -r|--remote-path)
            [ "$#" -ge 2 ] || fail "Missing value for $1"
            REMOTE_PATH="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "Unknown argument: $1"
            ;;
    esac
done

command -v "${ADB_BIN}" >/dev/null 2>&1 || fail "'${ADB_BIN}' not found on PATH."

adb_cmd get-state >/dev/null 2>&1 || fail "No adb device is available."

if [ -z "${TARGET}" ]; then
    TARGET="$(detect_target)"
fi

case "${TARGET}" in
    aarch64-linux-android|armv7-linux-androideabi|x86_64-linux-android)
        ;;
    *)
        fail "Unsupported target override: ${TARGET}"
        ;;
esac

LOCAL_BINARY="${BUILD_DIR}/${TARGET}/${BINARY_NAME}"
[ -f "${LOCAL_BINARY}" ] || fail "Built binary not found at ${LOCAL_BINARY}. Run ./gradlew :androidSshAgent:compileAndroidSshAgentAll first."

REMOTE_DIR="$(dirname "${REMOTE_PATH}")"

info "Device target: ${TARGET}"
info "Local binary: ${LOCAL_BINARY}"
info "Remote path: ${REMOTE_PATH}"

adb_cmd shell mkdir -p "${REMOTE_DIR}"
adb_cmd push "${LOCAL_BINARY}" "${REMOTE_PATH}" >/dev/null
adb_cmd shell chmod 755 "${REMOTE_PATH}"

cat <<EOF
Installed ${BINARY_NAME} to ${REMOTE_PATH}

Finish installation from a Termux shell:
  termux-setup-storage
  install -m 755 "${REMOTE_PATH}" "\$PREFIX/bin/${BINARY_NAME}"
  eval "\$("\$PREFIX/bin/${BINARY_NAME}" -a "\$PREFIX/tmp/keyguard-ssh-agent.sock")"

Then verify inside Termux:
  ssh-add -L

Direct execution from adb shell is not supported.
EOF
