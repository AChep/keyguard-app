---
title: Command line
description: The keyguard command-line tool, with its commands, flags, endpoints, exit codes, error messages and MCP tools.
section: developer
type: reference
order: 90
platforms: [linux, macos, windows]
related: [environments, coding-agents, agent-approvals]
---

`keyguard` is the command-line tool bundled with Keyguard on desktop. This page
lists its commands, flags, exit codes and errors, and the tools of its MCP
server. To set it up, see
[Run a command with an environment](/docs/environments/#run-a-command-with-an-environment).

## Commands

| Command | What it does |
| --- | --- |
| `keyguard help [<COMMAND>]` | Prints the help of a command, the same as `--help` |
| `keyguard mcp` | Runs the environment management MCP server over stdio |
| `keyguard run` | Runs a command with the variables of an environment |

- `-h`, `--help` prints help for any command.
- `-V`, `--version` works on `keyguard` alone.
- `keyguard` without a command prints help and exits with `2`.

## Run flags

```sh
keyguard run --environment <ENVIRONMENT_ID> [--account <ACCOUNT>] [--socket <PATH>] -- <COMMAND> [<ARG>...]
```

| Flag | Value | Description |
| --- | --- | --- |
| `-e`, `--environment` | Environment ID | Required. The ID that **Copy ID** copies. Keyguard matches the remote ID first, then the local one. |
| `-a`, `--account` | Account ID or exact name | Limits the search to one account. Use it when an ID matches environments in several accounts. |
| `--socket` | Path | Connects to Keyguard at this endpoint instead of the default |

The `--` is required. Flags go before it, and everything after it is the
command and its arguments.

## Run behavior

- The variables override inherited ones. An empty value is set, not removed.
- On Linux and macOS, `keyguard` replaces itself with the command. Signals,
  input, output and the exit code are the command's own.
- On Windows, Ctrl+C reaches the command. Closing `keyguard.exe` ends the
  command and the processes it started.
- On Windows, a bare command name is found through `PATHEXT`, so `.cmd` and
  `.bat` files work. If the environment sets `PATH`, the search uses it.
- On Windows, names that differ only in case are rejected. A variable,
  including its name, holds at most 32,767 characters.
- While Keyguard asks for approval, `keyguard` prints "waiting for approval in
  Keyguard…". It gives up after 90 seconds. Ctrl+C cancels the request.

## MCP server

```sh
keyguard mcp [--socket <PATH>]
```

The server supports MCP over standard input and output. It exits with `0` when
the client closes the connection, and with `1` after printing "Keyguard MCP
stopped. Open Keyguard and check the MCP configuration."

## MCP tools

| Tool | Arguments | Returns |
| --- | --- | --- |
| `authenticate` | None | The accounts you allow, with ID and name |
| `list_environments` | `account_id`, optional | Each environment's ID, account ID, name, variable count, and whether it's editable |
| `create_environment` | `account_id`, `name` | The new environment's ID and account ID |
| `rename_environment` | `environment_id`, `name`; optional `account_id` | The environment's ID and account ID |
| `list_variables` | `environment_id`; optional `account_id` | Each variable's name, source (`value`, `reference`, `unset` or `unsupported`) and whether it's set |
| `append_variables` | `environment_id`, `variables`: a list of `name` and an optional `value`; optional `account_id` | The environment's ID and account ID. Updates existing names in place and appends new ones. Without `value`, an existing value stays and a new variable is unset. |
| `create_local_env_file` | `environment_id`, `path`; optional `account_id` | The file's ID, environment ID, account ID, path, and status. Linux and macOS only. |
| `list_local_env_files` | Optional `environment_id` and `account_id` | Each file's ID, environment ID, account ID, path, status, and whether it's enabled |

- IDs are the ones **Copy ID** copies. `account_id` also accepts an exact
  unique account name.
- Keep each environment's `account_id` with its ID and pass both when selecting it.
  Without `account_id`, an ID shared by multiple accounts returns `ambiguous_environment`.
  A supplied account restricts lookup to that account.
- `list_local_env_files` lists all files without filters, one account's files with
  `account_id`, or one environment's files with `environment_id` and optional `account_id`.
  Files whose environment is unavailable appear only without filters, with `account_id: null`.
- No tool returns values, referenced items or file contents.
- `append_variables` can't create or overwrite a reference.
- There are no tools to delete, export or run commands.

## Environment variables

| Variable | Description |
| --- | --- |
| `KEYGUARD_CLI_SOCKET` | The endpoint to connect to, the same as `--socket`. The flag wins, and an empty value is ignored. |

## Endpoints

`keyguard` finds the running app at the default endpoint for your platform.

| Platform | Endpoint |
| --- | --- |
| Linux | `$XDG_RUNTIME_DIR/keyguard/cli.sock`, or `/tmp/keyguard-<UID>/cli.sock` without `XDG_RUNTIME_DIR` |
| Linux, Flatpak | `$XDG_RUNTIME_DIR/app/com.artemchep.keyguard/cli.sock` |
| macOS | `$HOME/.keyguard/cli.sock` |
| Windows | `\\.\pipe\keyguard-cli-<HASH>`, unique to your Windows account |

## Bundled executable

- `keyguard run` works only from the `keyguard` file bundled with the app, or
  an identical copy. A copy stops working when an update replaces the bundled
  file.
- `keyguard mcp` works from any copy.
- The check is best effort on every platform. The approval in Keyguard is
  what protects the values.

## Exit codes

| Code | Meaning |
| --- | --- |
| The command's code | `keyguard run` started the command, and it exited with this code |
| `0` | Help or version printed, or the MCP client closed the connection |
| `1` | Keyguard refused or failed the request. `keyguard` prints the reason. |
| `2` | Usage error, such as a missing flag or `--` |
| `126` | The command was found but can't start |
| `127` | The command wasn't found |

## Error messages

`keyguard` prints errors to standard error, after `keyguard:`.

| Message | Fix |
| --- | --- |
| "A change in Keyguard revoked the approval. Try again." | Run the command again. Changes to settings, permissions or the environment in Keyguard revoke pending approvals. |
| "A variable is too large for Windows." | Shorten the value. Windows limits a variable to 32,767 characters. |
| "Account not found." | Check the ID or name passed to `--account`. |
| "cannot run `<COMMAND>`: …" | The command exists but can't start. Check its permissions. |
| "command not found: `<COMMAND>`" | Check the name, or pass the full path. |
| "Command-line access is turned off. Turn it on in Keyguard's Developer settings." | Turn on **Enable command-line access** in **Settings > Developer > Command line**. |
| "Confirm your identity in Keyguard and try again." | Confirm your identity in Keyguard, then run the command again. |
| "Environment not found. Use Copy ID in Keyguard to get its ID." | Copy the ID again. Environments in the trash aren't found. |
| "Environments in several accounts have this ID. Choose one with --account." | Add `--account`. |
| "Invalid environment or command." | Pass a non-empty ID, and a command after `--`. |
| "Keyguard could not provide the environment (`<CODE>`)." | Update `keyguard` to match the app. The code names the cause. |
| "Keyguard has too many open connections. Close unused MCP clients and try again." | Close MCP clients you don't use. |
| "Keyguard is not running, or its CLI agent is stopped." | See [Troubleshooting](/docs/environments/#troubleshooting). |
| "Keyguard only accepts this request from its own keyguard executable." | Use the bundled tool, or copy it again after an update. |
| "run is not supported inside a Flatpak sandbox." | Run `keyguard` on the host. The Flatpak build doesn't support `keyguard run`. |
| "Several accounts have this name. Use the account ID." | Pass the account ID to `--account`. |
| "Some variables are unset or reference an item that is missing or hidden." | Set each unset variable, and fix broken references. |
| "The environment is too large." | Remove variables. An environment holds at most 64 KiB. |
| "The request was denied in Keyguard." | Run the command again and approve it. |
| "The request was not approved in time." | Run the command again and approve it before the prompt expires. |
| "This environment contains unsupported data." | Update Keyguard. A newer version saved data this one can't read. |
| "This environment exceeds the variable count or name length limit. Edit it in Keyguard." | Remove variables or shorten names. |
| "This Keyguard build does not support keyguard run." | Use another build. See [Limitations](/docs/environments/#limitations). |
| "This version of Keyguard does not support this command. Update Keyguard and try again." | Update Keyguard to match the tool. |
| "Two variable names differ only in case, which Windows treats as one." | Rename one of the variables. |
| "Unlock Keyguard and try again." | Unlock the vault, then run the command again. |
| "You don't have permission to read this environment." | The environment is in a collection that hides passwords. Ask an organization admin to change the collection's permissions. |
