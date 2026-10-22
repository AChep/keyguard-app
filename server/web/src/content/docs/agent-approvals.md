---
title: Agent approvals
description: "How long Keyguard remembers an approval for the SSH and GPG agents, the command line and MCP, and who can reuse it."
section: developer
type: concept
order: 30
platforms: [android, linux, macos, windows]
related: [ssh-agent, gpg-agent, environments, cli, coding-agents]
---

An approval lets a caller use a key or an environment from your vault. The
window sets how long Keyguard remembers an approval, and the scope sets which
callers can reuse it.

## Approval prompt

When a caller asks to use a key, Keyguard shows which app wants which key. A
request for an environment shows **Environment access request**, the caller,
and what it asks for. For MCP, the client's name is marked as unverified,
because the client reports it itself.

A prompt that you don't answer within 60 seconds counts as denied. Locking the
vault denies open environment requests.

## Approval window

The **Remember key approvals** setting sets how long Keyguard remembers an
approval for an agent. The command line and MCP each have a
**Remember approvals** setting.

| Requester | Setting in | Options | Default | Platforms |
| --- | --- | --- | --- | --- |
| SSH agent | **Settings > Developer > SSH agent** | **Ask every time**, 1, 5 or 15 minutes, 1 or 4 hours, **Until vault locks** | 5 minutes | Android, Linux, macOS, Windows |
| GPG agent | **Settings > Developer > GPG agent** | **Ask every time**, 1, 5 or 15 minutes, **Until vault locks** | 5 minutes | Android, Linux, macOS, Windows |
| Command line | **Settings > Developer > Command line** | **Ask every time**, 5 or 15 minutes, 1 or 4 hours, **Until vault locks** | **Until vault locks** | Linux, macOS, Windows |
| MCP and local .env files | **Settings > Developer > Environments > MCP** | Same as the command line | **Until vault locks** | Linux, macOS, Windows |

A remembered approval covers one key and the callers that the scope allows.
For the GPG agent, signing and decryption need separate approvals. Locking the
vault clears every remembered approval. Changing an approval setting clears
the approvals it governs.

## Approval scope

The **Approval scope** setting sets which callers can reuse an SSH or GPG agent
approval. The default is **Per application, isolated by terminal session**.

| Scope | Who can reuse? | Same tab or pane | Other tab or pane |
| --- | --- | --- | --- |
| **Per connection** | Current live agent connection | Current connection only | Current connection only |
| **Per process** | Same verified process instance | Same process only | Same process only |
| **Per application** | Same verified application | Shared | Shared |
| **Per application, isolated by terminal session** | Same verified terminal session; verified application outside terminals | Shared | Not shared |

A new command usually opens a new process and a new connection, so the first
two scopes ask again, even in the same terminal tab.

The terminal columns apply to Linux and macOS. If Keyguard can't verify the
caller, an approval covers only the current connection, for both agents. In
rare cases, such as a caller that changes while Keyguard checks it, Keyguard
asks every time.

## Command-line approvals

A `keyguard run` approval covers one environment in one terminal tab or pane.
Any later command in that tab gets the environment without a prompt, including
a coding agent started there. Each tmux pane and split pane counts as its own
tab.

The approval ends when the window ends, after 10 minutes unused, or after 12
hours at most. Locking or unlocking the vault ends it, as does turning
command-line access, MCP or a local .env file on or off. Changing the
environment or an item it references also ends it.

Keyguard asks for every run on Windows, outside a terminal, such as in an IDE
task, and on Linux kernels older than 6.5. These can't identify a terminal tab.

## MCP and local .env file approvals

Each running `keyguard mcp` process is one client. It first needs the
**Connect to Keyguard** approval, which also lets it list environments. Then
each kind of request needs its own approval, for one environment or path.
Creating environments is approved per account. A new process asks again.

A local .env file has its own approval. It never inherits the approval of the
client that created it. Once you approve a file, any process running as you
can read it.

These approvals end when the window ends, or when you lock the vault. They
also end when you turn MCP on or off, create or disable a local .env file, or
access to the environment or a referenced item changes. Editing an environment
keeps client approvals. A local .env file asks again after its environment or
a referenced item changes.

## Platform differences

| Platform | SSH agent | GPG agent |
| --- | --- | --- |
| Linux, macOS | All four scopes | All four scopes |
| Windows | Three scopes, without the terminal-isolated one. **Per application** is the default, and its approvals last while the detected app runs. | No **Approval scope** setting. An approval covers one connection. |
| Android, Termux helper | No **Approval scope** setting. An approval covers the verified app, or the current connection if Keyguard can't verify the app. | Same as the SSH agent |
| Android, other apps | Approval windows and scopes don't apply | Approval windows and scopes don't apply |

The native macOS app has no **Remember approvals** settings for the command
line and MCP. Both use the default, **Until vault locks**.

:::caution
On Windows, process and application detection is best effort. Another program
running under your account can pass as the approved one. **Per connection**
gives the strictest isolation.
:::
