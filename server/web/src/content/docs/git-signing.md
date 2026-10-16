---
title: Sign Git commits
description: "Sign Git commits with a GPG or SSH key from your vault, through Keyguard's GPG or SSH agent."
section: developer
type: how-to
order: 40
platforms: [linux, macos, windows]
related: [gpg-agent, ssh-agent, agent-approvals, gpg-keys]
---

Git records whatever name and email you configure, so anyone can make a commit
that looks like yours. A signature proves that a commit came from your key and
hasn't changed since. GitHub and GitLab mark signed commits as **Verified** when
the key is in your account.

To sign Git commits with a key from your vault, configure Git to sign through
Keyguard's GPG agent or SSH agent. The private key stays in your vault.

### Before you begin

- For a GPG key: the GPG agent is set up as described in
  [Set up the agent on desktop](/docs/gpg-agent/#set-up-the-agent-on-desktop),
  and GnuPG has your public key, as described in
  [Import the public key](/docs/gpg-agent/#import-the-public-key).
- For an SSH key: the SSH agent is set up as described in
  [Set up the agent on desktop](/docs/ssh-agent/#set-up-the-agent-on-desktop).

## Configure Git for a GPG key

In the repository, run these commands. Replace `<KEY_FINGERPRINT>` with the
fingerprint from your **GPG Key** item. To sign in every repository, use
`--global` instead of `--local`.

```sh
git config --local user.signingkey <KEY_FINGERPRINT>
git config --local commit.gpgsign true
git config --local gpg.format openpgp
git config --local gpg.program gpg
```

Git clients such as VS Code can use a different `PATH` or `GNUPGHOME` from
your terminal. On Linux and macOS, use a wrapper to select the same GnuPG
executable and home directory for every Git invocation:

1. In the terminal where signing works, find both paths:

   ```sh
   command -v gpg
   printf '%s\n' "$GNUPGHOME"
   ```

2. Save this script as `~/.local/bin/keyguard-gpg`, creating the directory
   if needed. Replace both placeholders with the absolute paths above:

   ```sh
   #!/bin/sh
   exec "<GPG_PATH>" --homedir "<GNUPG_HOME>" --no-autostart "$@"
   ```

3. Make the wrapper executable:

   ```sh
   chmod +x "$HOME/.local/bin/keyguard-gpg"
   ```

4. Point Git at the wrapper:

   ```sh
   git config --local gpg.program "$HOME/.local/bin/keyguard-gpg"
   ```

`--no-autostart` prevents GnuPG from starting its own agent when Keyguard is
unavailable. Setting an absolute `gpg.program` alone doesn't select Keyguard's
home directory.

### Windows

Git for Windows bundles its own `gpg`, which doesn't work with Keyguard. In
PowerShell, point Git at native GnuPG instead:

```powershell
$gpgProgram = (Get-Command gpg.exe -CommandType Application).Source
if ($gpgProgram -like "*\Git\usr\bin\gpg.exe") {
  throw "Configure PATH to use native GnuPG first."
}
git config --local gpg.program "$gpgProgram"
```

For GUI clients, also set `GNUPGHOME` in your Windows user environment
variables to the home from [Set up the agent on desktop](/docs/gpg-agent/#set-up-the-agent-on-desktop).
Restart the Git client afterward.

## Configure Git for an SSH key

In the repository, run these commands. Replace `<PUBLIC_KEY>` with the
**Public key** from your **SSH Key** item. To sign in every repository, use
`--global` instead of `--local`.

```sh
git config --local gpg.format ssh
git config --local user.signingkey "key::<PUBLIC_KEY>"
git config --local commit.gpgsign true
```

Git needs a list of trusted keys to show SSH signatures as good. Add your key to
it once:

```sh
echo "<EMAIL> <PUBLIC_KEY>" >> ~/.ssh/allowed_signers
git config --global gpg.ssh.allowedSignersFile ~/.ssh/allowed_signers
```

### Windows

In PowerShell, point Git at Windows OpenSSH's `ssh-keygen`:

```powershell
$sshKeygen = (Get-Command ssh-keygen.exe -CommandType Application).Source
if ($sshKeygen -like "*\Git\usr\bin\ssh-keygen.exe") {
  throw "Configure PATH to use Windows OpenSSH first."
}
git config --local gpg.ssh.program "$sshKeygen"
```

## Sign a commit

1. Commit as usual, for example with `git commit`. Git asks Keyguard's agent
   for a signature.
2. If the vault is locked, unlock it when Keyguard asks.
3. In Keyguard's prompt, check the program and the key, then approve the
   request. Git creates the signed commit.

Keyguard remembers an approval for a while, so the next commits don't ask
again. [Agent approvals](/docs/agent-approvals/) covers how long an approval
lasts and which programs can reuse it. A prompt that you don't answer within
60 seconds counts as denied, and Git doesn't create the commit.

## Verify

Run `git log --show-signature -1`. The last commit shows a good signature from
your key.
