---
title: SSH agent setup
description: Use SSH keys stored in your vault to authenticate — socket paths, SSH_AUTH_SOCK, and the Android helper.
category: guides
order: 5
---

Keyguard can act as an **SSH agent**: keys stored in your vault sign SSH
authentication requests, so private keys never sit unprotected on disk. Each
signing request can pop up an approval dialog telling you which application
wants to use which key. You set the approval window — from prompting on
**every request**, to remembering approvals for a set time (5 minutes by
default), or **until the vault locks**.

Keyguard stores keys in the dedicated **SSH key** item type. You can generate
new keys with the [generator](/docs/generator/) or import existing ones
(OpenSSH and PEM formats, including passphrase-protected keys). **Ed25519**
and **RSA** keys are supported.

## Desktop (Linux & macOS)

1. Enable the **SSH agent** in Keyguard's security settings. Keyguard starts
   its agent and listens on a local socket:
   - **Linux** — `$XDG_RUNTIME_DIR/keyguard-ssh-agent.sock` (or
     `/tmp/keyguard-$UID/ssh-agent.sock` if `XDG_RUNTIME_DIR` is unset);
   - **macOS** —
     `~/Library/Group Containers/com.artemchep.keyguard/ssh-agent.sock`.
2. Point your SSH tooling at it by setting `SSH_AUTH_SOCK` to Keyguard's
   socket path — the setup screen offers this as an option. For example, in
   your shell profile:

   ```sh
   export SSH_AUTH_SOCK="$XDG_RUNTIME_DIR/keyguard-ssh-agent.sock"
   ```

3. That's it — `ssh`, `git`, and anything else speaking the OpenSSH agent
   protocol will list your vault's keys (`ssh-add -l`) and trigger Keyguard's
   approval dialog when they sign.

> **Windows** — the agent would listen on the
> `\\.\pipe\keyguard-ssh-agent` named pipe, but the desktop SSH agent
> runtime is not available on Windows yet.

## Android

On Android the agent ships as a dedicated **helper package** for
[Termux](https://termux.dev/):

1. Enable the **SSH agent** in Keyguard's settings.
2. Install Termux, then install the Keyguard SSH agent helper package from
   the custom APT repository (the setup screen walks you through it).
3. Use `ssh` inside Termux as usual — the helper signals Keyguard, the two
   exchange encrypted messages over a local channel, and Keyguard shows the
   approval dialog.

## Reviewing activity

Keyguard keeps a history of agent requests, so you can review which
applications asked for signatures and whether each request succeeded, was
denied, or referenced an unknown key.
