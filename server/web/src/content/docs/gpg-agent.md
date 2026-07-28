---
title: GPG agent setup
description: Use GPG keys stored in your vault to sign and decrypt — GNUPGHOME, GnuPG's agent socket, and Git commit signing.
category: guides
order: 5
---

Keyguard can act as a **GPG agent**: keys stored in your vault sign and
decrypt data, so private keys never stored unprotected on disk. Each request can
pop up an approval dialog telling you which application wants to use which key.
You set the approval window — from prompting on **every request**, to
remembering approvals for a set time (one minute by default), or **until the
vault locks**.

Keyguard stores keys in the dedicated **GPG key** item type. You can generate
new keys with the [generator](/docs/generator/) — **Ed25519 + X25519** or
**RSA** (3072 or 4096 bits) — or import existing ones. Public keys can also be
fetched from a keyserver (`keys.openpgp.org` by default).

## Desktop (Linux, macOS & Windows)

1. Enable the **GPG agent** in Keyguard's GPG settings, and make sure the
   vault holds a GPG key the agent is allowed to use. Keyguard starts its agent
   on the native endpoint that GnuPG resolves for a home directory it manages:
   - **Linux** — `$XDG_RUNTIME_DIR/keyguard-gpg-agent` (or
     `/tmp/keyguard-$(id -u)/gnupg` if `XDG_RUNTIME_DIR` is unset); **Flatpak** — `~/.var/app/com.artemchep.keyguard/data/gnupg`;
   - **macOS** — `~/Library/Group Containers/com.artemchep.keyguard/gnupg`;
   - **Windows** — `%LOCALAPPDATA%\ArtemChepurnyi\keyguard\gnupg`.

   > **Windows requires native GnuPG.** The GPG executable bundled with Git for
   > Windows uses MSYS path handling and is not compatible with this setup.
   > Confirm that `where.exe gpg` and `where.exe gpgconf` resolve to your native
   > GnuPG installation, normally under `Program Files\GnuPG\bin`.

2. Point GnuPG at Keyguard by setting `GNUPGHOME` to the managed directory —
   the setup screen prints the exact path for your platform. For example, in
   your shell profile:

   ```sh
   export GNUPGHOME="$XDG_RUNTIME_DIR/keyguard-gpg-agent"
   ```

   For the Flatpak build, use the persistent app data directory instead:

   ```sh
   export GNUPGHOME="$HOME/.var/app/com.artemchep.keyguard/data/gnupg"
   ```

   In PowerShell on Windows:

   ```powershell
   $env:GNUPGHOME = "$env:LOCALAPPDATA\ArtemChepurnyi\keyguard\gnupg"
   ```

   Keyguard speaks the standard gpg-agent protocol on the socket reported by
   `gpgconf --homedir "$GNUPGHOME" --list-dirs agent-socket`, so any `gpg`
   command run with this `GNUPGHOME` reaches your vault's keys. Native Windows
   GnuPG resolves a marker-file endpoint backed by a loopback connection;
   Keyguard publishes that endpoint automatically.
3. Export the public key from the **GPG key** item and import it into this
   home — only public key material leaves the vault:

   ```sh
   gpg --import /path/to/keyguard-public-key.asc
   gpg --no-autostart --list-secret-keys --with-keygrip --keyid-format=long
   ```

4. Verify the agent is serving keys, then sign a short message (replace
   `YOUR_KEY_FINGERPRINT` with the fingerprint from the imported key):

   ```sh
   GPG_AGENT_SOCKET="$(gpgconf --homedir "$GNUPGHOME" --list-dirs agent-socket)"
   gpg-connect-agent --raw-socket "$GPG_AGENT_SOCKET" "KEYINFO --list" /bye
   printf "Keyguard GPG agent test\n" | gpg --no-autostart --local-user YOUR_KEY_FINGERPRINT --clearsign
   ```

   On Windows, use the socket path resolved by the native `gpgconf.exe`:

   ```powershell
   $env:GPG_AGENT_SOCKET = & gpgconf --homedir "$env:GNUPGHOME" --list-dirs agent-socket
   gpg-connect-agent --raw-socket "$env:GPG_AGENT_SOCKET" "KEYINFO --list" /bye
   "Keyguard GPG agent test" | gpg --no-autostart --local-user YOUR_KEY_FINGERPRINT --clearsign
   ```

   The first signature triggers Keyguard's approval dialog.

## Signing Git commits

Use the same `GNUPGHOME` when Git signs. Keep the config local to one
repository, or swap `--local` for `--global`:

```sh
git config --local user.signingkey YOUR_KEY_FINGERPRINT
git config --local commit.gpgsign true
git config --local gpg.format openpgp
git config --local gpg.program gpg
```

Then commit with that home in the environment:

```sh
GNUPGHOME="$XDG_RUNTIME_DIR/keyguard-gpg-agent" git commit -S
```

For the Flatpak build:

```sh
GNUPGHOME="$HOME/.var/app/com.artemchep.keyguard/data/gnupg" git commit -S
```

On Windows PowerShell:

```powershell
$gpgProgram = (Get-Command gpg.exe -CommandType Application).Source
if ($gpgProgram -like "*\Git\usr\bin\gpg.exe") {
  throw "Configure PATH to use native GnuPG first."
}
git config --local gpg.program "$gpgProgram"
$env:GNUPGHOME = "$env:LOCALAPPDATA\ArtemChepurnyi\keyguard\gnupg"
git commit -S
```

> **Android** — you can store GPG key items and search keyservers, but there
> is no agent to serve keys to a local `gpg`.

## Approval scopes

The approval window controls **how long** an approval is remembered. The
approval scope controls **which verified callers** may reuse it during that
window. Choose a scope in the GPG agent settings:

| Scope                                                     | Who can reuse an approval? | Same terminal tab or pane | Different terminal tab or pane |
|-----------------------------------------------------------| --- | --- | --- |
| **Per connection**                                        | Current live agent connection | Current connection only | Current connection only |
| **Per process**                                           | Same verified process instance | Same process only | Same process only |
| **Per application**                                       | Same verified application | Shared | Shared |
| **Application, isolated by terminal session** *(default)* | Same verified terminal session; verified application outside terminals | Shared | Not shared |

Starting a new command normally creates a new process and agent connection, so
the connection and process scopes usually ask again even in the same terminal
tab or pane.

The terminal columns describe Linux and macOS when native identity evidence is
available. On Windows, every option currently behaves like **Per
connection**.

## Reviewing activity

Keyguard keeps a history of GPG agent requests, so you can review which
applications asked to sign or decrypt, which key they used, and whether each
request succeeded or was denied. Filters let you restrict which keys and
callers the agent will serve.
