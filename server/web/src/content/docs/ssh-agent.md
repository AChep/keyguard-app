---
title: SSH agent setup
description: Use SSH keys stored in your vault from desktop tools, Termux, and Android apps.
category: guides
order: 6
---

Keyguard can act as an **SSH agent**: keys stored in your vault sign SSH
authentication requests, so private keys are never stored unprotected on disk. Each
signing request can pop up an approval dialog telling you which application
wants to use which key. You set the approval window — from prompting on
**every request**, to remembering approvals for a set time (5 minutes by
default), or **until the vault locks**.

Keyguard stores keys in the dedicated **SSH key** item type. You can generate
new keys with the [generator](/docs/generator/) or import existing ones
(OpenSSH and PEM formats, including passphrase-protected keys). **Ed25519**
and **RSA** keys are supported.

## Desktop (Linux, macOS & Windows)

1. Enable the **SSH agent** in Keyguard's security settings. Keyguard starts
   its agent and listens on a local endpoint:
   - **Linux** — `$XDG_RUNTIME_DIR/keyguard-ssh-agent.sock` (or
     `/tmp/keyguard-$UID/ssh-agent.sock` if `XDG_RUNTIME_DIR` is unset;
     Flatpak builds use
     `$XDG_RUNTIME_DIR/app/com.artemchep.keyguard/ssh-agent.sock`);
   - **macOS** —
     `~/.keyguard/ssh-agent.sock`;
   - **Windows** — `\\.\pipe\keyguard-ssh-agent`.
2. Point your SSH tooling at it by setting `SSH_AUTH_SOCK` to Keyguard's
   endpoint — the setup screen offers this as an option. For example, in your
   shell profile:

   ```sh
   export SSH_AUTH_SOCK="$XDG_RUNTIME_DIR/keyguard-ssh-agent.sock"
   ```

   On macOS:

   ```sh
   export SSH_AUTH_SOCK="$HOME/.keyguard/ssh-agent.sock"
   ```

   Or set the macOS endpoint in `~/.ssh/config`:

   ```sshconfig
   Host *
     IdentityAgent "${HOME}/.keyguard/ssh-agent.sock"
   ```

   In PowerShell on Windows:

   ```powershell
   $env:SSH_AUTH_SOCK="\\.\pipe\keyguard-ssh-agent"
   ```

   Or set the Windows endpoint in `~/.ssh/config`:

   ```sshconfig
   Host *
     IdentityAgent \\.\pipe\keyguard-ssh-agent
   ```

3. That's it — `ssh`, `git`, and anything else speaking the OpenSSH agent
   protocol will list your vault's keys (`ssh-add -l`) and trigger Keyguard's
   approval dialog when they sign.

## Android

### Termux

For OpenSSH in [Termux](https://termux.dev/), the agent uses a dedicated
**helper package**:

1. Enable the **SSH agent** in Keyguard's settings.
2. Install Termux, then install the Keyguard SSH agent helper package from
   the custom APT repository (the setup screen walks you through it).
3. Use `ssh` inside Termux as usual — the helper signals Keyguard, the two
   exchange encrypted messages over a local channel, and Keyguard shows the
   approval dialog.

### Other apps

Android apps that support the OpenKeychain/OpenIntents SSH Authentication API
can use Keyguard directly. This path does not need Termux, the helper package,
or a local SSH agent socket.

1. Enable **SSH agent** in Keyguard's SSH settings. The provider is visible to
   Android apps only while this switch is enabled.
2. Choose **Keyguard** as the SSH authentication or key provider in the other
   app. The exact name of this setting depends on the app.
3. Approve the first registration request. Keyguard may then ask you to choose
   an eligible key, unlock the vault, or authenticate for a signing request.
4. Review or revoke registered apps from **Connected apps** in either the SSH
   or GPG settings.

## Agent approval scopes

These approval-window and scope settings govern the standard desktop agent and
the Termux helper. Direct Android SSH Authentication API registrations and
request approvals do not reuse these scopes.

The approval window controls **how long** an approval is remembered. The
approval scope controls **which verified callers** may reuse it during that
window. Choose a scope in the SSH agent settings:

| Scope | Who can reuse an approval? | Same terminal tab or pane | Different terminal tab or pane |
| --- | --- | --- | --- |
| **Per connection** | Current live agent connection | Current connection only | Current connection only |
| **Per process** | Same verified process instance | Same process only | Same process only |
| **Per application** | Same verified application | Shared | Shared |
| **Application, isolated by terminal session** *(default)* | Same verified terminal session; verified application outside terminals | Shared | Not shared |

Starting a new command normally creates a new process and agent connection, so
the connection and process scopes usually ask again even in the same terminal
tab or pane.

The terminal columns describe Linux and macOS when native identity evidence is
available. With the Termux helper on Android, **Per process** falls back to the
current connection, while **Application, isolated by terminal session** falls
back to the verified application because Android does not provide a
terminal-session identity here. On Windows, every option currently behaves
like **Per connection**.

## Reviewing activity

Keyguard keeps a history of agent and direct API requests, so you can review
which applications asked for signatures and whether each request succeeded,
was denied, or referenced an unknown key.
