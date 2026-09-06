---
title: GPG agent setup
description: Use GPG keys stored in your vault from desktop tools and Android apps.
category: guides
order: 5
---

Keyguard can act as a **GPG agent**: keys stored in your vault sign and
decrypt data, so private keys are never stored unprotected on disk. Each request can
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
   vault holds a GPG key the agent is allowed to use. Keyguard creates a
   dedicated GnuPG home for the integration. These are `GNUPGHOME` directories,
   not agent socket endpoints:
   - **Linux** — `$XDG_DATA_HOME/keyguard/gnupg` (or
     `~/.local/share/keyguard/gnupg` if `XDG_DATA_HOME` is unset, empty, or relative); **Flatpak** —
     `~/.var/app/com.artemchep.keyguard/data/gnupg`;
   - **macOS** — `~/.keyguard/gnupg`;
   - **Windows** — `%LOCALAPPDATA%\ArtemChepurnyi\keyguard\gnupg`.

   > **Windows requires native GnuPG.** The GPG executable bundled with Git for
   > Windows uses MSYS path handling and is not compatible with this setup.
   > Confirm that `where.exe gpg` and `where.exe gpgconf` resolve to your native
   > GnuPG installation, normally under `Program Files\GnuPG\bin`.

2. Point GnuPG at Keyguard by setting `GNUPGHOME` to the managed directory —
   the setup screen prints the exact path for your platform. For example, in
   your shell profile:

   ```sh
   case "${XDG_DATA_HOME:-}" in
     /*) GNUPGHOME="$XDG_DATA_HOME/keyguard/gnupg" ;;
     *) GNUPGHOME="$HOME/.local/share/keyguard/gnupg" ;;
   esac
   GNUPGHOME="$(printf '%s' "$GNUPGHOME" | tr -s '/')"
   export GNUPGHOME
   ```

   On macOS:

   ```sh
   export GNUPGHOME="$HOME/.keyguard/gnupg"
   ```

   For the Flatpak build, use the persistent app data directory instead:

   ```sh
   export GNUPGHOME="$HOME/.var/app/com.artemchep.keyguard/data/gnupg"
   ```

   In PowerShell on Windows:

   ```powershell
   $env:GNUPGHOME = "$env:LOCALAPPDATA\ArtemChepurnyi\keyguard\gnupg"
   ```

   Keyguard speaks the standard gpg-agent protocol on the separate endpoint
   reported by `gpgconf --homedir "$GNUPGHOME" --list-dirs agent-socket`.
   GnuPG may place that endpoint under a per-user runtime directory instead of
   inside `GNUPGHOME`; Keyguard therefore does not construct the socket path
   from the home directory. If `gpgconf` cannot resolve an absolute endpoint or
   prepare its required socket directory, Keyguard reports a startup error
   instead of publishing a socket that GnuPG clients cannot discover. Native
   Windows GnuPG resolves a marker-file endpoint backed by a loopback
   connection; Keyguard publishes that endpoint automatically.
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

Then commit from a shell where `GNUPGHOME` is exported as in step 2:

```sh
git commit -S
```

On macOS:

```sh
GNUPGHOME="$HOME/.keyguard/gnupg" git commit -S
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

## Android apps

On Android, Keyguard can be selected as an **OpenPGP provider** by apps that
support the OpenKeychain API.

1. Open Keyguard's **GPG settings** and enable **GPG agent**. Android publishes
   Keyguard as an OpenPGP provider only while this switch is enabled. Check the
   GPG agent filters if a key you expect is not offered.
2. In the other app's encryption or OpenPGP settings, choose **Keyguard** as the
   OpenPGP provider. The exact menu and wording depend on the app.
3. Approve the first registration request in Keyguard. Later requests may ask
   you to select eligible keys, unlock the vault, or authenticate when private
   key access is required.
4. Review or revoke registered apps from **Connected apps** in Keyguard's GPG
   settings. Registrations are tied to the app's signing certificate; if its
   signer changes, access is disabled until you revoke the old registration.

Compatible apps can ask Keyguard to select recipients or signing keys, provide
public keys, sign or verify data, and encrypt or decrypt it. The calling app never gets access to the private key.
Apps known to include integrations for this API include
[Thunderbird for Android and K-9 Mail](https://github.com/thunderbird/thunderbird-android/tree/main/plugins/openpgp-api-lib)
and [FairEmail](https://github.com/M66B/FairEmail/tree/master/openpgp-api).
These are compatibility examples rather than a guarantee for every app version;
the client must let you choose an OpenPGP provider instead of requiring the
OpenKeychain app specifically.

## Desktop approval scopes

The following settings apply to the standard desktop `gpg-agent`. Direct
Android provider registrations and request approvals do not use these remembered
approval scopes.

The desktop approval window controls **how long** an approval is remembered.
The approval scope controls **which verified callers** may reuse it during that
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

Keyguard keeps a history of desktop agent and Android provider requests, so you
can review which applications asked to sign or decrypt, which key they used,
and whether each request succeeded or was denied. Filters let you restrict
which keys and callers Keyguard will serve.
