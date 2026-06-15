#!/usr/bin/env bash
#
# test-ssh-agent.sh — Integration smoke-test for the Keyguard SSH agent.
#
# Exercises the SSH agent socket with standard OpenSSH tools to verify that
# key listing and signing work end-to-end.
#
# Prerequisites:
#   - Keyguard desktop app is running with the vault unlocked and at least
#     one SSH key stored.
#   - ssh-add and ssh-keygen are available on PATH.
#
# Usage:
#   ./test-ssh-agent.sh              # auto-detect socket path
#   ./test-ssh-agent.sh /path/to/sock  # explicit socket path
#
# NOTE: The signing test (Test 2) triggers an approval dialog inside the
#       Keyguard app. You have 60 seconds to click "Approve" before the
#       request is auto-denied.

set -euo pipefail

# ── Colours (disabled when stdout is not a terminal) ─────────────────────────

if [ -t 1 ]; then
    GREEN='\033[0;32m'
    RED='\033[0;31m'
    YELLOW='\033[0;33m'
    CYAN='\033[0;36m'
    BOLD='\033[1m'
    RESET='\033[0m'
else
    GREEN='' RED='' YELLOW='' CYAN='' BOLD='' RESET=''
fi

# ── Helpers ──────────────────────────────────────────────────────────────────

pass()   { echo -e "  ${GREEN}PASS${RESET}  $1"; }
fail()   { echo -e "  ${RED}FAIL${RESET}  $1"; }
skip()   { echo -e "  ${YELLOW}SKIP${RESET}  $1"; }
info()   { echo -e "  ${CYAN}INFO${RESET}  $1"; }
header() { echo -e "\n${BOLD}── $1 ──${RESET}"; }

TESTS_PASSED=0
TESTS_FAILED=0
TESTS_SKIPPED=0

record_pass() { TESTS_PASSED=$((TESTS_PASSED + 1)); pass "$1"; }
record_fail() { TESTS_FAILED=$((TESTS_FAILED + 1)); fail "$1"; }
record_skip() { TESTS_SKIPPED=$((TESTS_SKIPPED + 1)); skip "$1"; }

# ── Socket detection ─────────────────────────────────────────────────────────

detect_socket() {
    # 1. Explicit argument
    if [ -n "${1:-}" ]; then
        echo "$1"
        return
    fi

    # 2. Platform defaults
    case "$(uname -s)" in
        Darwin)
            echo "$HOME/Library/Group Containers/com.artemchep.keyguard/ssh-agent.sock"
            ;;
        Linux)
            if [ -n "${XDG_RUNTIME_DIR:-}" ]; then
                echo "${XDG_RUNTIME_DIR}/keyguard-ssh-agent.sock"
            else
                echo "/tmp/keyguard-$(id -u)/ssh-agent.sock"
            fi
            ;;
        *)
            echo ""
            ;;
    esac
}

SOCKET_PATH="$(detect_socket "${1:-}")"

# ── Prerequisite checks ─────────────────────────────────────────────────────

header "Prerequisite checks"

if [ -z "$SOCKET_PATH" ]; then
    fail "Could not determine socket path. Pass it as the first argument."
    exit 1
fi

info "Socket path: ${SOCKET_PATH}"

if [ ! -e "$SOCKET_PATH" ]; then
    fail "Socket does not exist at: ${SOCKET_PATH}"
    info "Make sure the Keyguard desktop app is running."
    exit 1
fi

if [ ! -S "$SOCKET_PATH" ]; then
    fail "${SOCKET_PATH} exists but is not a Unix domain socket."
    exit 1
fi

pass "Socket exists and is a Unix domain socket."

for cmd in ssh-add ssh-keygen; do
    if ! command -v "$cmd" &>/dev/null; then
        fail "'$cmd' not found on PATH."
        exit 1
    fi
done

pass "ssh-add and ssh-keygen are available."

# Point all subsequent SSH operations at the Keyguard agent.
export SSH_AUTH_SOCK="$SOCKET_PATH"

# ── Test 1: List identities ─────────────────────────────────────────────────

header "Test 1 — List identities (request_identities)"

LIST_OUTPUT="$(ssh-add -l 2>&1)" || true

if echo "$LIST_OUTPUT" | grep -q "The agent has no identities"; then
    record_skip "Agent returned no identities (vault may be locked or empty)."
    info "Unlock the vault and add at least one SSH key, then re-run."
    NUM_KEYS=0
elif echo "$LIST_OUTPUT" | grep -q "Could not open a connection"; then
    record_fail "Could not connect to the agent."
    info "Output: $LIST_OUTPUT"
    NUM_KEYS=0
elif echo "$LIST_OUTPUT" | grep -q "Error connecting"; then
    record_fail "Connection error."
    info "Output: $LIST_OUTPUT"
    NUM_KEYS=0
else
    NUM_KEYS="$(echo "$LIST_OUTPUT" | wc -l | tr -d ' ')"
    record_pass "Agent reports ${NUM_KEYS} key(s)."
    echo ""
    info "Fingerprints (ssh-add -l):"
    echo "$LIST_OUTPUT" | while IFS= read -r line; do
        echo "        $line"
    done
fi

# Also exercise ssh-add -L (full public keys) if we have keys.
if [ "$NUM_KEYS" -gt 0 ]; then
    PUBKEYS_OUTPUT="$(ssh-add -L 2>&1)" || true
    if [ -n "$PUBKEYS_OUTPUT" ] && ! echo "$PUBKEYS_OUTPUT" | grep -qi "error\|no identities"; then
        record_pass "ssh-add -L returned full public key(s)."
    else
        record_fail "ssh-add -L failed or returned no keys."
        info "Output: $PUBKEYS_OUTPUT"
    fi
fi

# ── Test 2: Sign a test message ─────────────────────────────────────────────

header "Test 2 — Sign data (sign request)"

if [ "$NUM_KEYS" -eq 0 ]; then
    record_skip "No keys available, skipping signing test."
else
    # Extract the first public key for signing.
    FIRST_PUBKEY="$(ssh-add -L | head -n 1)"
    KEY_TYPE="$(echo "$FIRST_PUBKEY" | awk '{print $1}')"
    info "Signing with key type: ${KEY_TYPE}"

    TMPDIR_TEST="$(mktemp -d)"
    trap 'rm -rf "$TMPDIR_TEST"' EXIT

    PUBKEY_FILE="${TMPDIR_TEST}/test_key.pub"
    ALLOWED_SIGNERS="${TMPDIR_TEST}/allowed_signers"
    MESSAGE_FILE="${TMPDIR_TEST}/message.txt"
    SIGNATURE_FILE="${TMPDIR_TEST}/message.txt.sig"

    echo "$FIRST_PUBKEY" > "$PUBKEY_FILE"
    echo "test@keyguard $FIRST_PUBKEY" > "$ALLOWED_SIGNERS"
    echo "Hello from Keyguard SSH agent integration test!" > "$MESSAGE_FILE"

    echo ""
    info "${YELLOW}>>> An approval dialog should appear in the Keyguard app. <<<${RESET}"
    info "${YELLOW}>>> Click 'Approve' within 60 seconds to continue.       <<<${RESET}"
    echo ""

    if ssh-keygen -Y sign \
        -f "$PUBKEY_FILE" \
        -n "test-keyguard" \
        < "$MESSAGE_FILE" \
        > "$SIGNATURE_FILE" 2>/dev/null; then
        record_pass "Signing succeeded."
        SIGN_OK=1
    else
        record_fail "Signing failed (denied or timed out?)."
        info "Make sure you approved the request in the Keyguard app."
        SIGN_OK=0
    fi

    # ── Test 3: Verify the signature ─────────────────────────────────────

    header "Test 3 — Verify signature"

    if [ "$SIGN_OK" -eq 0 ]; then
        record_skip "No signature to verify (signing did not succeed)."
    elif [ ! -s "$SIGNATURE_FILE" ]; then
        record_fail "Signature file is empty."
    else
        if ssh-keygen -Y verify \
            -f "$ALLOWED_SIGNERS" \
            -I "test@keyguard" \
            -n "test-keyguard" \
            -s "$SIGNATURE_FILE" \
            < "$MESSAGE_FILE" 2>/dev/null; then
            record_pass "Signature verification succeeded."
        else
            record_fail "Signature verification failed."
        fi
    fi
fi

# ── Summary ──────────────────────────────────────────────────────────────────

header "Summary"

TOTAL=$((TESTS_PASSED + TESTS_FAILED + TESTS_SKIPPED))
echo -e "  Total:   ${TOTAL}"
echo -e "  ${GREEN}Passed:  ${TESTS_PASSED}${RESET}"
echo -e "  ${RED}Failed:  ${TESTS_FAILED}${RESET}"
echo -e "  ${YELLOW}Skipped: ${TESTS_SKIPPED}${RESET}"
echo ""

if [ "$TESTS_FAILED" -gt 0 ]; then
    echo -e "  ${RED}${BOLD}Some tests failed.${RESET}"
    exit 1
elif [ "$TESTS_SKIPPED" -eq "$TOTAL" ]; then
    echo -e "  ${YELLOW}${BOLD}All tests were skipped — unlock the vault and add SSH keys first.${RESET}"
    exit 0
else
    echo -e "  ${GREEN}${BOLD}All tests passed.${RESET}"
    exit 0
fi
