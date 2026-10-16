---
title: SSH agent
description: "Sign SSH requests with keys from your vault, from desktop tools, Termux and Android apps."
section: developer
type: feature
order: 10
platforms: [android, linux, macos, windows]
related: [agent-approvals, git-signing, gpg-agent, generator]
---

Keyguard can act as an SSH agent for OpenSSH clients such as `ssh` and `git`.
The private key never leaves your vault: a client sends Keyguard the data to
sign and gets back only the signature. No key file sits on disk for other
programs to read or copy.

Keyguard asks you to approve requests and logs every request.
[Agent approvals](/docs/agent-approvals/) covers when Keyguard remembers an
approval.

### Before you begin

- An **SSH Key** item in your vault, holding an Ed25519 or RSA key. The
  [Generator](/docs/generator/) creates one, and Keyguard imports OpenSSH and
  PEM keys, with or without a passphrase.
- **SSH agent** turned on in **Settings > Developer > SSH agent**.

## Set up the agent on desktop

**Settings > Developer > SSH agent > SSH client setup** shows these commands
for your platform.

1. Add this line to your shell profile, with your platform's path from
   [Endpoints](#endpoints):

   ```sh
   export SSH_AUTH_SOCK="<ENDPOINT>"
   ```

   On Windows, add this line to your PowerShell profile:

   ```powershell
   $env:SSH_AUTH_SOCK="\\.\pipe\keyguard-ssh-agent"
   ```

2. Open a new shell.
3. Run `ssh-add -L`. It lists the public keys that Keyguard serves.

Instead of `SSH_AUTH_SOCK`, you can set `IdentityAgent` in `~/.ssh/config`.
In this file, write environment variables in braces, such as `${HOME}`. On
macOS:

```sshconfig
Host *
  IdentityAgent "${HOME}/.keyguard/ssh-agent.sock"
```

On Windows, leave the pipe path unquoted:

```sshconfig
Host *
  IdentityAgent \\.\pipe\keyguard-ssh-agent
```

## Set up the agent in Termux

Termux reaches Keyguard through a helper package.

1. Install [Termux](https://termux.dev/).
2. In Keyguard, open **Settings > Developer > SSH agent > SSH client setup**.
3. In Termux, run the commands under **Install package**. They add Keyguard's
   APT repository and install `keyguard-android-ssh-agent`.
4. Add these lines to your Termux shell startup file, such as `~/.bashrc`:

   ```sh
   if [ -x "$PREFIX/bin/keyguard-android-ssh-agent" ]; then
     eval "$("$PREFIX/bin/keyguard-android-ssh-agent" --ensure -a "$PREFIX/tmp/keyguard-ssh-agent.sock")"
   fi
   ```

5. Open a new Termux session.
6. Run `ssh-add -L`. It lists the public keys that Keyguard serves.

When `ssh` uses a key, Keyguard asks for your approval.

## Use the agent from other Android apps

Apps that support the OpenKeychain SSH Authentication API use Keyguard
directly, without Termux. They see Keyguard only while **SSH agent** is on.

1. In the other app, select **Keyguard** as the SSH key provider. The
   setting's name depends on the app.
2. In Keyguard, approve the registration request.

Later requests can ask you to choose a key, unlock the vault or authenticate.
To revoke an app, select it in **Settings > Developer > Connected apps**, then
select **Revoke**.

## Review request history

In **Settings > Developer > SSH agent**, select **Request log**. Each entry
shows the caller, the key, the request and its result, such as **Success**,
**Denied** or **Key not found**.

To see the requests for one key, open its **SSH Key** item and select
**View SSH agent history**.

## Endpoints

| Platform | Endpoint |
| --- | --- |
| Android, Termux | `$PREFIX/tmp/keyguard-ssh-agent.sock` |
| Linux | `$XDG_RUNTIME_DIR/keyguard-ssh-agent.sock` |
| Linux, Flatpak | `$XDG_RUNTIME_DIR/app/com.artemchep.keyguard/ssh-agent.sock` |
| macOS | `$HOME/.keyguard/ssh-agent.sock` |
| Windows | `\\.\pipe\keyguard-ssh-agent` |
