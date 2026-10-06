package com.artemchep.keyguard.feature.gpgagent.help

/** Run by the user in Terminal, where HOME and gpgconf belong to external GnuPG.
 * The application never accesses the user's default keyring or launches GnuPG. */
fun macosSandboxGpgAgentSetupCommand(agentSocket: String): String {
    require(agentSocket.startsWith('/') && agentSocket.none { it == '\u0000' || it == '\n' || it == '\r' })
    val quotedSocket = "'" + agentSocket.replace("'", "'\"'\"'") + "'"
    return $$"""
        /bin/sh <<'KEYGUARD_GPG_SETUP'
        set -eu
        umask 077
        fail() { printf '%s\n' "$*" >&2; exit 1; }
        check_directory() {
          [ -d "$1" ] && [ ! -L "$1" ] || fail "Not a regular directory: $1"
          [ "$(stat -f '%u' "$1")" = "$(id -u)" ] || fail "Directory belongs to another user: $1"
          mode=$(stat -f '%Lp' "$1")
          [ "$((0$mode & 0022))" = 0 ] || fail "Directory is writable by another user: $1"
        }
        make_directory() {
          if [ ! -e "$1" ] && [ ! -L "$1" ]; then mkdir -m 700 "$1"; fi
          check_directory "$1"
        }
        command -v gpgconf >/dev/null || fail 'Install GnuPG first and make gpgconf available in PATH.'
        keyguard_socket=$${quotedSocket}
        # Keyguard supplies this path only after its helper reports readiness.
        # macOS may deny external metadata access to an App Group even when
        # connecting to its socket through the link below is permitted.
        make_directory "$HOME/.keyguard"
        GNUPGHOME="$HOME/.keyguard/gnupg"
        export GNUPGHOME
        make_directory "$GNUPGHOME"
        [ "$(stat -f '%Lp' "$GNUPGHOME")" = 700 ] || fail "GnuPG home must have mode 700: $GNUPGHOME"
        # GnuPG may relocate sockets outside GNUPGHOME. Let the installed version
        # prepare its own socket directory, then query the final endpoint.
        # Some macOS builds return failure when GNUPGHOME is the usable fallback.
        # Validate the final directory below, whether creation succeeded or not.
        gpgconf --homedir "$GNUPGHOME" --create-socketdir >/dev/null 2>&1 || :
        endpoint=$(gpgconf --homedir "$GNUPGHOME" --list-dirs agent-socket)
        case "$endpoint" in /*) ;; *) fail 'gpgconf returned an invalid agent endpoint.' ;; esac
        [ "$endpoint" != "$keyguard_socket" ] || fail 'GnuPG endpoint must differ from the Keyguard socket.'
        check_directory "$(dirname "$endpoint")"
        if [ -L "$endpoint" ]; then
          [ "$(readlink "$endpoint")" = "$keyguard_socket" ] || fail "Refusing to replace an unrelated link: $endpoint"
        elif [ -e "$endpoint" ] || [ -S "$endpoint" ]; then
          fail "Refusing to replace an existing file or agent socket: $endpoint"
        fi
        config="$GNUPGHOME/gpg.conf"
        if [ -e "$config" ] || [ -L "$config" ]; then
          [ -f "$config" ] && [ ! -L "$config" ] || fail "Refusing to modify an unrelated file: $config"
          [ "$(stat -f '%u' "$config")" = "$(id -u)" ] || fail 'GnuPG configuration belongs to another user.'
          mode=$(stat -f '%Lp' "$config")
          [ "$((0$mode & 0022))" = 0 ] || fail 'GnuPG configuration is writable by another user.'
          if ! grep -Eq '^[[:space:]]*no-autostart([[:space:]]*(#.*)?)?$' "$config"; then
            printf '\nno-autostart\n' >> "$config"
          fi
        else
          (set -C; printf 'no-autostart\n' > "$config")
        fi
        if [ ! -L "$endpoint" ]; then ln -s "$keyguard_socket" "$endpoint"; fi
        printf 'Keyguard GPG agent configured: %s\n' "$endpoint"
        KEYGUARD_GPG_SETUP
    """.trimIndent()
}
