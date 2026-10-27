---
title: Environments
description: Keep environment variables in your vault and pass them to local tools through local .env files, keyguard run or MCP.
section: developer
type: feature
order: 70
platforms: all
stage: beta
related: [coding-agents, cli, agent-approvals, item-extras, sync-and-conflicts]
---

An **Environment** item holds the variables of a project, such as API keys and
database URLs. Keyguard passes them to local tools on request, so no plaintext
`.env` file sits on disk. You approve each request, and
[Agent approvals](/docs/agent-approvals/) covers how long Keyguard remembers one.

## Delivery methods

| Method | How a tool gets the values | Platforms |
| --- | --- | --- |
| Local .env file | Reads a named pipe at a path you choose. Each read gets the current values. | Linux, macOS |
| `keyguard run` | Starts with the variables in its environment | Linux, macOS, Windows |
| MCP | Never. A coding agent creates and edits environments without seeing values. See [Connect a coding agent](/docs/coding-agents/). | Linux, macOS, Windows |

## Create an environment

1. In the vault, select **New item** > **Environment**.
2. In **Save to**, choose the account.
3. Enter the **Environment name**.
4. Select **Add variable**, then **Value** or **Reference to an item**.
5. Enter the **Variable name**, then the value, or choose the item and field.
6. Select **Save**.

To use the environment from a tool, select **Copy ID** in the item's menu.

## Import a .env file

1. Open the environment's editor.
2. In the menu, select **Import .env**.
3. Select **Choose .env file**, or paste the file into **Paste .env content**.
4. Check the names under **Variables to import**, then select
   **Replace variables**.

The import replaces all variables of the environment. You can also drop a
`.env` file onto the editor. On the native macOS and iOS apps, select
**Preview import** before **Replace variables**.

:::caution
Keyguard keeps the source file. Delete it, and rotate every secret that was
committed or shared before.
:::

## Create a local .env file

1. In the environment's menu, select **Create local .env file**.
2. Enter the **Absolute path of the new .env file**, or select
   **Choose folder**.
3. Select **Create local .env file**.

On the native macOS app, select **Create local .env file** in the editor, then
choose the folder. Keyguard names the file `.env`.

To stop a file, open **Settings > Developer > Environments > Local .env files**,
then select **Disable local .env file** in the file's menu.

### Flatpak

Flatpak needs access to the folder first. The **Create local .env file** dialog
shows the command for the path you enter.

1. On the host, run:

   ```sh
   flatpak override --user --filesystem='<FOLDER>' com.artemchep.keyguard
   ```

2. Restart Keyguard.
3. Create the file in that folder.

## Run a command with an environment

1. In **Settings > Developer > Command line**, turn on
   **Enable command-line access**.
2. Link the file under **Location of the command-line tool** into a folder on
   your `PATH`:

   ```sh
   ln -s "<CLI_PATH>" ~/.local/bin/keyguard
   ```

   On Windows, add the folder that holds `keyguard.exe` to `PATH`. On
   AppImage, copy the file instead, and copy it again after each update.
3. In the environment's menu, select **Copy ID**.
4. Run your command after `--`:

   ```sh
   keyguard run --environment <ENVIRONMENT_ID> -- npm start
   ```

5. In Keyguard, select **Approve**.

The command gets the variables on top of the ones it inherits. Keyguard doesn't
write them to disk. [Command line](/docs/cli/) lists the flags, exit codes and
errors.

## Verify

To check a local .env file, read it and approve the request. It prints the
variables:

```sh
cat <PATH>
```

To check `keyguard run`, print one variable:

```sh
keyguard run --environment <ENVIRONMENT_ID> -- printenv <NAME>
```

On Windows, in PowerShell:

```powershell
keyguard run --environment <ENVIRONMENT_ID> -- powershell -c '$env:<NAME>'
```

## Variables

| Type | Delivers |
| --- | --- |
| **Value** | The stored text, as typed. It can be empty. |
| **Reference to an item** | The current password or custom field of another item |
| **Unset** | Nothing. Delivery fails until you set a value. |

- Names use ASCII letters, digits and underscores, and start with a letter or
  an underscore. They're case-sensitive, with at most 255 characters.
- An environment holds up to 1,024 variables and 64 KiB of data.
- Values are literal: Keyguard doesn't expand `$NAME` inside them. Turn off
  interpolation in tools that expand variables, such as `interpolate=False` in
  python-dotenv.

## References

- A reference points to another item in the same account. That item must be
  synced, out of the trash, and not an environment.
- It reads the login password, or a **Text**, **Text (concealed)** or
  **Boolean** custom field with a unique name.
- Keyguard resolves it on every read, so it follows changes to the item.
- Delivery fails if the item or field is gone, the field name repeats, or a
  collection hides the item's passwords.
- Only a new environment without references can move to another account.
- For cross-account copy rules, see [Bulk actions](/docs/bulk-actions/).

## Local .env files

- The path must be unused. Keyguard never overwrites a file.
- The folder must belong to you, and other users can't write to it.
- A path serves one environment. Up to 10 files can be enabled per device.
- The file is a named pipe that only you can open. Use one reader at a time.
- The filesystem must support atomic file exchange.
- Keyguard removes the files when it quits, and creates them again when it
  starts. A read while the vault is locked waits until you unlock and approve.
- A denied or failed read gets an empty file. Keyguard never writes an error
  into it.

## .env syntax

Import reads this syntax:

- One `NAME=value` per line, with an optional `export` prefix and spaces around
  `=`.
- `#` starts a comment line, or a comment after a space in an unquoted value.
- `NAME` alone, without `=`, adds an unset variable.
- Single quotes keep the text as is. Double quotes decode `\n`, `\r`, `\t`,
  `\\`, `\"` and `\$`. Both can span lines.
- A duplicate or invalid name rejects the import with "Cannot read this .env
  content".

A local .env file writes each value in single quotes, unquoted, or in double
quotes, whichever keeps it intact in Node dotenv and python-dotenv. A value
that none of them keep intact blocks delivery. The stored value doesn't
change.

## Limitations

| Build or platform | Limitation |
| --- | --- |
| Windows | No local .env files |
| Flatpak | No `keyguard run`. Local .env files need folder access first, as in [Flatpak](#flatpak). |
| Mac App Store | No `keyguard run` |
| AppImage | The command-line tool's path changes on every launch. Copy the tool, and point MCP clients at the copy. |
| Android, iOS | No delivery: no local .env files, `keyguard run` or MCP. |

## Troubleshooting

### "Unavailable. Check that the path is free and its folder is writable"

Keyguard can't serve the named pipe at that path. Something else took the
path, the folder isn't writable, or the filesystem lacks atomic file exchange.

Disable the file, then create it again at an unused path in a folder you own,
on a local disk.

### keyguard reports "Keyguard is not running, or its CLI agent is stopped"

`keyguard` can't reach Keyguard. The CLI agent runs only while command-line
access, MCP or a local .env file is on.

Start Keyguard and turn on **Enable command-line access**. On Linux over SSH
or `sudo`, `XDG_RUNTIME_DIR` can be unset, so pass the
[Endpoints](/docs/cli/#endpoints) path with `--socket`.

### keyguard reports "Some variables are unset or reference an item that is missing or hidden"

A variable can't be resolved. Set every **Unset** variable. Check that each
referenced item exists, is out of the trash, and still has the field.

### A tool reads an empty .env file

Keyguard refused the read: you denied it, the prompt expired after 60 seconds,
or a variable can't be resolved. Read the file again and approve the request.
If it stays empty, check the variables as in the previous entry.
