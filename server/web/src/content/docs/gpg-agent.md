---
title: GPG agent
description: "Sign and decrypt with GPG keys from your vault, from GnuPG on desktop and OpenPGP apps on Android."
section: developer
type: feature
order: 20
platforms: [android, linux, macos, windows]
related: [git-signing, agent-approvals, gpg-keys, ssh-agent]
---

Keyguard can act as a GPG agent for GnuPG on desktop and for OpenPGP apps on
Android. The private key never leaves your vault: a program sends Keyguard what
it needs signed or decrypted and gets back only the result. GnuPG's keyring
holds only your public key, so no secret key sits on disk for other programs to
read or copy.

Keyguard asks you to approve requests and logs every request.
[Agent approvals](/docs/agent-approvals/) covers when Keyguard remembers a
desktop approval.

### Before you begin

- A **GPG Key** item in your vault that holds a private key. The
  [Generator](/docs/generator/) creates one, and [GPG keys](/docs/gpg-keys/)
  lists what Keyguard imports.
- **GPG agent** turned on in **Settings > Developer > GPG agent**.
- On desktop, GnuPG installed. On Windows, `where.exe gpg` and
  `where.exe gpgconf` must show native GnuPG, normally in
  `Program Files\GnuPG\bin`. The `gpg` bundled with Git for Windows doesn't
  work.

## Set up the agent on desktop

**Settings > Developer > GPG agent > GPG client setup** shows these commands
for your platform.

1. Add the `GNUPGHOME` line for your platform, listed below, to your shell
   profile.
2. Open a new shell.

### Linux

The agent's home is `$XDG_DATA_HOME/keyguard/gnupg`, or
`~/.local/share/keyguard/gnupg` when `XDG_DATA_HOME` is unset, empty or
relative. GnuPG finds the agent by the exact path, so this command also
removes repeated slashes:

```sh
case "${XDG_DATA_HOME:-}" in
  /*) GNUPGHOME="$XDG_DATA_HOME/keyguard/gnupg" ;;
  *) GNUPGHOME="$HOME/.local/share/keyguard/gnupg" ;;
esac
GNUPGHOME="$(printf '%s' "$GNUPGHOME" | tr -s '/')"
export GNUPGHOME
```

In the Flatpak build:

```sh
export GNUPGHOME="$HOME/.var/app/com.artemchep.keyguard/data/gnupg"
```

### macOS

```sh
export GNUPGHOME="$HOME/.keyguard/gnupg"
```

### Windows

Add this line to your PowerShell profile:

```powershell
$env:GNUPGHOME = "$env:LOCALAPPDATA\ArtemChepurnyi\keyguard\gnupg"
```

## Import the public key

GnuPG needs your public key to use the private key in Keyguard. Only the public
key leaves your vault.

1. Export the public key from the **GPG Key** item. Keyguard saves a
   `.public.asc` file.
2. Import the file into GnuPG:

   ```sh
   gpg --import <PUBLIC_KEY_FILE>
   ```

3. List the secret keys:

   ```sh
   gpg --no-autostart --list-secret-keys --with-keygrip --keyid-format=long
   ```

   Your key appears in the list.

## Sign a test message

1. Sign a message with your key's fingerprint:

   ```sh
   printf "Keyguard GPG agent test\n" | gpg --no-autostart --local-user <KEY_FINGERPRINT> --clearsign
   ```

   In PowerShell on Windows:

   ```powershell
   "Keyguard GPG agent test" | gpg --no-autostart --local-user <KEY_FINGERPRINT> --clearsign
   ```

2. In Keyguard, approve the request. `gpg` prints the signed message.

To sign commits with this key, see [Sign Git commits](/docs/git-signing/).

## Use the agent from Android apps

Apps that support the OpenKeychain API can select Keyguard as their OpenPGP
provider. They see Keyguard only while **GPG agent** is on.

1. In the other app's OpenPGP settings, select **Keyguard** as the OpenPGP
   provider. The setting's name depends on the app.
2. In Keyguard, approve the registration request.

Later requests can ask you to choose keys, unlock the vault or authenticate.
Apps can select recipients and signing keys, read public keys, sign, verify,
encrypt and decrypt. They never get the private key.

Apps with this integration include
[Thunderbird for Android and K-9 Mail](https://github.com/thunderbird/thunderbird-android/tree/main/plugins/openpgp-api-lib)
and [FairEmail](https://github.com/M66B/FairEmail/tree/master/openpgp-api).
Support varies between app versions.

To revoke an app, select it in **Settings > Developer > Connected apps**, then
select **Revoke**. If an app's signing certificate changes, the app loses access
until you revoke its old registration.

## Review request history

In **Settings > Developer > GPG agent**, select **Request log**. It lists
requests from desktop tools and Android apps, with the caller, the key, the
operation and the result.

## Troubleshooting

### "Failed to start the GPG agent"

Keyguard can't find GnuPG or prepare its socket directory. The
**GPG agent** status shows **Failed**.

Install GnuPG. Keyguard looks for `gpgconf` on your `PATH`, and on macOS and
Windows also in the usual install folders. If GnuPG is elsewhere on macOS or
Windows, set the `KEYGUARD_GPG_BIN_DIR` environment variable to its `bin`
folder, then restart Keyguard.

### gpg reports "No secret key"

GnuPG doesn't reach Keyguard, or Keyguard doesn't serve the key.

Check that the program running GnuPG uses Keyguard's GnuPG home and
**GPG agent** is on. Check that the **GPG Key** item holds a private key and
that **GPG agent filters** include it.

If Git signing works in your terminal but fails in a Git client, follow
[Configure Git for a GPG key](/docs/git-signing/#configure-git-for-a-gpg-key).

To list the keys that Keyguard serves, run:

```sh
GPG_AGENT_SOCKET="$(gpgconf --homedir "$GNUPGHOME" --list-dirs agent-socket)"
gpg-connect-agent --raw-socket "$GPG_AGENT_SOCKET" "KEYINFO --list" /bye
```

In PowerShell on Windows:

```powershell
$env:GPG_AGENT_SOCKET = & gpgconf --homedir "$env:GNUPGHOME" --list-dirs agent-socket
gpg-connect-agent --raw-socket "$env:GPG_AGENT_SOCKET" "KEYINFO --list" /bye
```
