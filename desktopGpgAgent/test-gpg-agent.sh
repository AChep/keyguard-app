#!/usr/bin/env bash
#
# test-gpg-agent.sh - Integration smoke-test for the Keyguard GPG agent.
#
# Exercises the GPG agent socket with standard GnuPG tools to verify that
# Assuan key listing, signing, verification, and decryption work end-to-end.
#
# Prerequisites:
#   - Keyguard desktop app is running with the GPG agent enabled.
#   - The vault is unlocked with at least one GPG key stored.
#   - The key's public material is imported into the GNUPGHOME used below.
#   - gpg, gpg-connect-agent, and gpgconf are available on PATH.
#
# Usage:
#   ./test-gpg-agent.sh                         # auto-detect GNUPGHOME
#   ./test-gpg-agent.sh /path/to/gnupg          # explicit GNUPGHOME
#   ./test-gpg-agent.sh /path/to/S.gpg-agent    # explicit agent socket
#   ./test-gpg-agent.sh /path/to/gnupg FPR      # explicit key selector
#
# NOTE: The signing and decryption tests trigger approval dialogs inside the
#       Keyguard app. You have 60 seconds to click "Approve" before each
#       request is auto-denied.

set -euo pipefail

# -- Colours (disabled when stdout is not a terminal) -------------------------

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

# -- Helpers -----------------------------------------------------------------

pass()   { printf '  %bPASS%b  %s\n' "$GREEN" "$RESET" "$1"; }
fail()   { printf '  %bFAIL%b  %s\n' "$RED" "$RESET" "$1"; }
skip()   { printf '  %bSKIP%b  %s\n' "$YELLOW" "$RESET" "$1"; }
info()   { printf '  %bINFO%b  %s\n' "$CYAN" "$RESET" "$1"; }
header() { printf '\n%b-- %s --%b\n' "$BOLD" "$1" "$RESET"; }

TESTS_PASSED=0
TESTS_FAILED=0
TESTS_SKIPPED=0

record_pass() { TESTS_PASSED=$((TESTS_PASSED + 1)); pass "$1"; }
record_fail() { TESTS_FAILED=$((TESTS_FAILED + 1)); fail "$1"; }
record_skip() { TESTS_SKIPPED=$((TESTS_SKIPPED + 1)); skip "$1"; }

trim_trailing_slash() {
    local path="$1"
    while [ "${#path}" -gt 1 ] && [ "${path%/}" != "$path" ]; do
        path="${path%/}"
    done
    echo "$path"
}

find_gpg_key_for_capability() {
    local capability="$1"
    local selector="${2:-}"

    printf '%s\n' "$SECRET_KEYS_OUTPUT" | awk -F: \
        -v capability="$capability" \
        -v selector="$selector" '
        function has_cap(caps) {
            return index(tolower(caps), tolower(capability)) > 0
        }
        function selector_matches(value) {
            return selector == "" || index(tolower(value), tolower(selector)) > 0
        }
        function flush_primary() {
            if (primary_seen && group_has_cap && group_matches) {
                if (primary_fpr != "") {
                    print primary_fpr
                } else {
                    print primary_keyid
                }
                found = 1
                exit
            }
        }

        $1 == "sec" {
            flush_primary()
            primary_seen = 1
            primary_keyid = $5
            primary_fpr = ""
            group_has_cap = has_cap($12)
            group_matches = selector_matches($5)
            pending = "sec"
            next
        }

        $1 == "ssb" {
            if (primary_seen) {
                if (has_cap($12)) {
                    group_has_cap = 1
                }
                if (selector_matches($5)) {
                    group_matches = 1
                }
            }
            pending = "ssb"
            next
        }

        $1 == "fpr" {
            if (pending == "sec") {
                primary_fpr = $10
                if (selector_matches($10)) {
                    group_matches = 1
                }
            } else if (pending == "ssb") {
                if (selector_matches($10)) {
                    group_matches = 1
                }
            }
            next
        }

        END {
            if (!found) {
                flush_primary()
            }
        }'
}

print_secret_key_summary() {
    printf '%s\n' "$SECRET_KEYS_OUTPUT" | awk -F: '
        $1 == "sec" {
            keyid = $5
            capabilities = $12
            pending = "sec"
            next
        }

        $1 == "fpr" && pending == "sec" {
            printf "        %s  %s  caps=%s\n", $10, keyid, capabilities
            pending = ""
            next
        }'
}

gpg_home_from_socket() {
    local socket_path="$1"
    dirname "$socket_path"
}

gpg_socket_from_home() {
    local gpg_home="$1"
    local socket_path
    socket_path="$(gpgconf --homedir "$gpg_home" --list-dirs agent-socket 2>/dev/null || true)"
    if [ -n "$socket_path" ]; then
        echo "$socket_path"
    else
        echo "${gpg_home}/S.gpg-agent"
    fi
}

# -- GNUPGHOME / socket detection -------------------------------------------

GPG_HOME=""
SOCKET_PATH=""
AUTO_DETECT_CANDIDATES=()

add_candidate_home() {
    AUTO_DETECT_CANDIDATES+=("$(trim_trailing_slash "$1")")
}

detect_default_paths() {
    local uid
    uid="$(id -u)"

    case "$(uname -s)" in
        Darwin)
            add_candidate_home "/tmp/keyguard-${uid}/gnupg"
            add_candidate_home "$HOME/Library/Group Containers/com.artemchep.keyguard/gnupg"
            ;;
        Linux)
            if [ "${container:-}" = "flatpak" ]; then
                if [ -n "${XDG_DATA_HOME:-}" ]; then
                    add_candidate_home "${XDG_DATA_HOME}/gnupg"
                else
                    add_candidate_home "${HOME}/.var/app/${FLATPAK_ID:-com.artemchep.keyguard}/data/gnupg"
                fi
            fi
            if [ -n "${XDG_RUNTIME_DIR:-}" ]; then
                add_candidate_home "${XDG_RUNTIME_DIR}/keyguard-gpg-agent"
            fi
            add_candidate_home "/tmp/keyguard-${uid}/gnupg"
            ;;
        *)
            return
            ;;
    esac

    local candidate
    for candidate in "${AUTO_DETECT_CANDIDATES[@]}"; do
        if [ -S "$(gpg_socket_from_home "$candidate")" ]; then
            GPG_HOME="$candidate"
            SOCKET_PATH="$(gpg_socket_from_home "$candidate")"
            return
        fi
    done

    if [ "${#AUTO_DETECT_CANDIDATES[@]}" -gt 0 ]; then
        GPG_HOME="${AUTO_DETECT_CANDIDATES[0]}"
        SOCKET_PATH="$(gpg_socket_from_home "$GPG_HOME")"
    fi
}

detect_paths() {
    local explicit="${1:-}"

    if [ -n "$explicit" ]; then
        explicit="$(trim_trailing_slash "$explicit")"
        case "$explicit" in
            */S.gpg-agent)
                SOCKET_PATH="$explicit"
                GPG_HOME="$(gpg_home_from_socket "$SOCKET_PATH")"
                ;;
            *)
                GPG_HOME="$explicit"
                SOCKET_PATH="$(gpg_socket_from_home "$GPG_HOME")"
                ;;
        esac
        return
    fi

    detect_default_paths
}

detect_paths "${1:-}"
KEY_SELECTOR="${2:-}"

# -- Prerequisite checks -----------------------------------------------------

header "Prerequisite checks"

if [ -z "$GPG_HOME" ] || [ -z "$SOCKET_PATH" ]; then
    fail "Could not determine GNUPGHOME. Pass it as the first argument."
    exit 1
fi

info "GNUPGHOME: ${GPG_HOME}"
info "Socket path: ${SOCKET_PATH}"

if [ "${#AUTO_DETECT_CANDIDATES[@]}" -gt 1 ]; then
    info "Auto-detected candidates:"
    for candidate in "${AUTO_DETECT_CANDIDATES[@]}"; do
        echo "        $(gpg_socket_from_home "$candidate")"
    done
fi

if [ -n "$KEY_SELECTOR" ]; then
    info "Key selector: ${KEY_SELECTOR}"
fi

if [ ! -e "$SOCKET_PATH" ]; then
    fail "Socket does not exist at: ${SOCKET_PATH}"
    info "Make sure the Keyguard desktop app is running and GPG agent is enabled."
    exit 1
fi

if [ ! -S "$SOCKET_PATH" ]; then
    fail "${SOCKET_PATH} exists but is not a Unix domain socket."
    exit 1
fi

pass "Socket exists and is a Unix domain socket."

for cmd in gpg gpg-connect-agent gpgconf; do
    if ! command -v "$cmd" &>/dev/null; then
        fail "'$cmd' not found on PATH."
        exit 1
    fi
done

pass "gpg, gpg-connect-agent, and gpgconf are available."

GPGCONF_SOCKET="$(GNUPGHOME="$GPG_HOME" gpgconf --list-dirs agent-socket 2>&1)" || {
    fail "gpgconf could not resolve the agent socket."
    info "Output: $GPGCONF_SOCKET"
    exit 1
}

if [ "$GPGCONF_SOCKET" != "$SOCKET_PATH" ]; then
    fail "gpgconf resolves a different agent socket."
    info "gpgconf: ${GPGCONF_SOCKET}"
    info "expected: ${SOCKET_PATH}"
    exit 1
fi

pass "gpgconf resolves the same agent socket."

# -- Test 1: Assuan socket responds -----------------------------------------

header "Test 1 - Assuan socket responds (GETINFO)"

GETINFO_OUTPUT="$(gpg-connect-agent \
    --raw-socket "$SOCKET_PATH" \
    "GETINFO socket_name" \
    /bye 2>&1)" || true

if echo "$GETINFO_OUTPUT" | grep -Fqx "D ${SOCKET_PATH}"; then
    record_pass "GETINFO socket_name returned the expected socket path."
elif echo "$GETINFO_OUTPUT" | grep -q "^OK"; then
    record_pass "GETINFO completed successfully."
    info "Output: $GETINFO_OUTPUT"
else
    record_fail "GETINFO failed."
    info "Output: $GETINFO_OUTPUT"
fi

# -- Test 2: List agent-visible keys ----------------------------------------

header "Test 2 - List agent keys (KEYINFO --list)"

KEYINFO_OUTPUT="$(gpg-connect-agent \
    --raw-socket "$SOCKET_PATH" \
    "KEYINFO --list" \
    /bye 2>&1)" || true

if echo "$KEYINFO_OUTPUT" | grep -q "^ERR "; then
    record_fail "KEYINFO --list returned an error."
    info "Output: $KEYINFO_OUTPUT"
    NUM_AGENT_KEYS=0
else
    NUM_AGENT_KEYS="$(echo "$KEYINFO_OUTPUT" | grep -c "^S KEYINFO " || true)"
    if [ "$NUM_AGENT_KEYS" -eq 0 ]; then
        record_skip "Agent returned no usable GPG keys."
        info "Unlock the vault, add a GPG key, and make sure GPG agent filters allow it."
    else
        record_pass "Agent reports ${NUM_AGENT_KEYS} usable keygrip(s)."
        echo ""
        info "Keygrips (KEYINFO --list):"
        echo "$KEYINFO_OUTPUT" | awk '
            /^S KEYINFO / {
                keygrip = $3
                fingerprint = $9
                flags = $11
                printf "        %s  fpr=%s  flags=%s\n", keygrip, fingerprint, flags
            }'
    fi
fi

# -- Test 3: Find GPG-resolvable secret keys --------------------------------

header "Test 3 - Find GPG keyring entries"

if [ "$NUM_AGENT_KEYS" -eq 0 ]; then
    record_skip "No agent keys available, skipping GPG keyring lookup."
    NUM_SECRET_KEYS=0
    SIGNING_KEY=""
    ENCRYPTION_KEY=""
else
    GPG_LIST_SECRET_ARGS=(
        --no-autostart
        --batch
        --yes
        --with-colons
        --with-keygrip
        --list-secret-keys
    )
    if [ -n "$KEY_SELECTOR" ]; then
        GPG_LIST_SECRET_ARGS+=("$KEY_SELECTOR")
    fi

    SECRET_KEYS_OUTPUT="$(GNUPGHOME="$GPG_HOME" gpg "${GPG_LIST_SECRET_ARGS[@]}" 2>&1)" || true

    if echo "$SECRET_KEYS_OUTPUT" | grep -q "^gpg: error reading key"; then
        record_fail "gpg failed to read the requested key selector."
        info "Output: $SECRET_KEYS_OUTPUT"
        NUM_SECRET_KEYS=0
    else
        NUM_SECRET_KEYS="$(printf '%s\n' "$SECRET_KEYS_OUTPUT" | awk -F: '$1 == "sec" { count++ } END { print count + 0 }')"

        if [ "$NUM_SECRET_KEYS" -eq 0 ]; then
            record_skip "No GPG secret-key stubs found in GNUPGHOME."
            info "Import the stored key's public key into: ${GPG_HOME}"
            SIGNING_KEY=""
            ENCRYPTION_KEY=""
        else
            record_pass "GPG resolves ${NUM_SECRET_KEYS} secret-key entr$( [ "$NUM_SECRET_KEYS" -eq 1 ] && echo "y" || echo "ies" )."
            echo ""
            info "GPG keys:"
            print_secret_key_summary

            SIGNING_KEY="$(find_gpg_key_for_capability "s" "$KEY_SELECTOR" | head -n 1)"
            ENCRYPTION_KEY="$(find_gpg_key_for_capability "e" "$KEY_SELECTOR" | head -n 1)"
        fi
    fi
fi

# -- Shared temp files -------------------------------------------------------

TMPDIR_TEST="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_TEST"' EXIT

# -- Test 4: Sign and verify ------------------------------------------------

header "Test 4 - Sign data (PKSIGN) and verify"

if [ "$NUM_SECRET_KEYS" -eq 0 ]; then
    record_skip "No GPG keyring entries available, skipping signing test."
elif [ -z "$SIGNING_KEY" ]; then
    record_skip "No signing-capable key matched the requested selector."
else
    MESSAGE_FILE="${TMPDIR_TEST}/message.txt"
    SIGNED_FILE="${TMPDIR_TEST}/message.txt.asc"
    SIGN_STDERR="${TMPDIR_TEST}/sign.stderr"
    VERIFY_STDERR="${TMPDIR_TEST}/verify.stderr"

    echo "Hello from Keyguard GPG agent integration test!" > "$MESSAGE_FILE"

    info "Signing with key: ${SIGNING_KEY}"
    echo ""
    info "${YELLOW}>>> An approval dialog should appear in the Keyguard app. <<<${RESET}"
    info "${YELLOW}>>> Click 'Approve' within 60 seconds to continue.       <<<${RESET}"
    echo ""

    if GNUPGHOME="$GPG_HOME" gpg \
        --no-autostart \
        --batch \
        --yes \
        --status-fd 2 \
        -u "$SIGNING_KEY" \
        --clearsign \
        < "$MESSAGE_FILE" \
        > "$SIGNED_FILE" \
        2> "$SIGN_STDERR"; then
        record_pass "Clearsign succeeded."

        if GNUPGHOME="$GPG_HOME" gpg \
            --no-autostart \
            --batch \
            --yes \
            --status-fd 2 \
            --verify "$SIGNED_FILE" \
            > /dev/null \
            2> "$VERIFY_STDERR" &&
            grep -Eq "GOODSIG|VALIDSIG" "$VERIFY_STDERR"; then
            record_pass "Signature verification succeeded."
        else
            record_fail "Signature verification failed."
            info "Output: $(cat "$VERIFY_STDERR")"
        fi
    else
        record_fail "Clearsign failed (denied or timed out?)."
        info "Make sure you approved the signing request in the Keyguard app."
        info "Output: $(cat "$SIGN_STDERR")"
    fi
fi

# -- Test 5: Encrypt and decrypt -------------------------------------------

header "Test 5 - Encrypt and decrypt (PKDECRYPT)"

if [ "$NUM_SECRET_KEYS" -eq 0 ]; then
    record_skip "No GPG keyring entries available, skipping decrypt test."
elif [ -z "$ENCRYPTION_KEY" ]; then
    record_skip "No encryption-capable key matched the requested selector."
else
    PLAIN_FILE="${TMPDIR_TEST}/plain.txt"
    CIPHER_FILE="${TMPDIR_TEST}/plain.txt.asc"
    DECRYPTED_FILE="${TMPDIR_TEST}/plain.decrypted.txt"
    ENCRYPT_STDERR="${TMPDIR_TEST}/encrypt.stderr"
    DECRYPT_STDERR="${TMPDIR_TEST}/decrypt.stderr"

    echo "keyguard-gpg-agent decrypt test $(date +%s)" > "$PLAIN_FILE"

    info "Encrypting for key: ${ENCRYPTION_KEY}"
    ENCRYPT_OK=0
    if GNUPGHOME="$GPG_HOME" gpg \
        --no-autostart \
        --batch \
        --yes \
        --trust-model always \
        --armor \
        -r "$ENCRYPTION_KEY" \
        --encrypt \
        < "$PLAIN_FILE" \
        > "$CIPHER_FILE" \
        2> "$ENCRYPT_STDERR"; then
        record_pass "Encryption succeeded."
        ENCRYPT_OK=1
    else
        record_fail "Encryption failed."
        info "Output: $(cat "$ENCRYPT_STDERR")"
    fi

    if [ "$ENCRYPT_OK" -eq 1 ] && [ -s "$CIPHER_FILE" ]; then
        echo ""
        info "${YELLOW}>>> An approval dialog should appear in the Keyguard app. <<<${RESET}"
        info "${YELLOW}>>> Click 'Approve' within 60 seconds to continue.       <<<${RESET}"
        echo ""

        if GNUPGHOME="$GPG_HOME" gpg \
            --no-autostart \
            --batch \
            --yes \
            --status-fd 2 \
            --decrypt "$CIPHER_FILE" \
            > "$DECRYPTED_FILE" \
            2> "$DECRYPT_STDERR"; then
            if cmp -s "$PLAIN_FILE" "$DECRYPTED_FILE"; then
                record_pass "Decryption round-trip succeeded."
            else
                record_fail "Decryption produced different plaintext."
            fi
        else
            record_fail "Decryption failed (denied or timed out?)."
            info "Make sure you approved the decryption request in the Keyguard app."
            info "Output: $(cat "$DECRYPT_STDERR")"
        fi
    elif [ "$ENCRYPT_OK" -eq 1 ]; then
        record_fail "Encryption reported success but produced no ciphertext."
    fi
fi

# -- Summary ----------------------------------------------------------------

header "Summary"

TOTAL=$((TESTS_PASSED + TESTS_FAILED + TESTS_SKIPPED))
printf '  Total:   %s\n' "$TOTAL"
printf '  %bPassed:  %s%b\n' "$GREEN" "$TESTS_PASSED" "$RESET"
printf '  %bFailed:  %s%b\n' "$RED" "$TESTS_FAILED" "$RESET"
printf '  %bSkipped: %s%b\n' "$YELLOW" "$TESTS_SKIPPED" "$RESET"
echo ""

if [ "$TESTS_FAILED" -gt 0 ]; then
    printf '  %bSome tests failed.%b\n' "${RED}${BOLD}" "$RESET"
    exit 1
elif [ "$TESTS_SKIPPED" -eq "$TOTAL" ]; then
    printf '  %bAll tests were skipped - enable the GPG agent and import public keys first.%b\n' "${YELLOW}${BOLD}" "$RESET"
    exit 0
elif [ "$TESTS_SKIPPED" -gt 0 ]; then
    printf '  %bCompleted with skipped tests.%b\n' "${YELLOW}${BOLD}" "$RESET"
    exit 0
else
    printf '  %bAll tests passed.%b\n' "${GREEN}${BOLD}" "$RESET"
    exit 0
fi
