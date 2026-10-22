---
title: Connect a coding agent
description: Let a coding agent create and edit your environments through MCP, and run its commands with their variables, without seeing values.
section: developer
type: how-to
order: 80
platforms: [linux, macos, windows]
stage: beta
related: [environments, cli, agent-approvals]
---

To let a coding agent manage your environments without seeing their values,
connect it to Keyguard's MCP server.

### Before you begin

- To run the agent's commands with an environment, set up
  [Run a command with an environment](/docs/environments/#run-a-command-with-an-environment)
  or [Create a local .env file](/docs/environments/#create-a-local-env-file).

## Turn on MCP

1. In **Settings > Developer > Environments > MCP**, turn on
   **Enable MCP access**. The status shows **Ready**.
2. Under **MCP client configuration**, copy the configuration.

The configuration starts `keyguard mcp` with the path of the bundled tool. On
Flatpak and the native macOS app, it adds the arguments those builds need.

## Add Keyguard to the agent

Paste the configuration into the agent's MCP settings. If the file already
lists servers, add the `keyguard` entry to its `mcpServers`.

| Agent | Where the configuration goes |
| --- | --- |
| Claude Code | `.mcp.json` in the project folder |
| Cursor | `.cursor/mcp.json` in the project folder, or `~/.cursor/mcp.json` for every project |
| Codex | `~/.codex/config.toml`, as a TOML table |
| Other agents | The agent's MCP server settings, with the stdio transport |

Codex takes the command and arguments of the configuration in TOML. For most
builds:

```toml
[mcp_servers.keyguard]
command = "<CLI_PATH>"
args = ["mcp"]
```

You can also add the server from the agent's command line. Use the command and
arguments from the configuration:

```sh
claude mcp add keyguard -- "<CLI_PATH>" mcp
codex mcp add keyguard -- "<CLI_PATH>" mcp
```

## Approve the agent's requests

1. Restart the agent, and ask it to list your Keyguard environments.
2. In Keyguard, select **Approve** for **Connect to Keyguard**.

Each new request names what the agent wants, such as **Create an environment**
or **Add or update environment variables**, and asks again.
[Agent approvals](/docs/agent-approvals/) covers how long Keyguard remembers
an approval. [MCP tools](/docs/cli/#mcp-tools) lists what the agent can do.

## Give the agent's commands the values

The agent never gets values through MCP. To run its code with them, use either:

- A local .env file. Ask the agent to create one in the project, then approve
  the request. The app's dotenv library reads it.
- `keyguard run`. Ask the agent to start commands with
  `keyguard run --environment <ENVIRONMENT_ID> -- <COMMAND>`.

A program running as you can read an approved .env file, and an approved
terminal tab serves every command in it. The agent can use both to see values.
For a prompt on every run, set **Remember approvals** in
**Settings > Developer > Command line** to **Ask every time**.

## Verify

Ask the agent to list the variables of an environment. It gets each name and
whether the variable holds a value, a reference or nothing, but never the
value.
