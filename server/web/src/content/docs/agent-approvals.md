---
title: Agent approvals
description: "How long Keyguard remembers an SSH or GPG agent approval, and which callers can reuse it."
section: developer
type: concept
order: 30
platforms: [android, linux, macos, windows]
related: [ssh-agent, gpg-agent]
---

An approval lets a caller use a key from your vault. Each agent has two
approval settings, in **Settings > Developer > SSH agent** and
**Settings > Developer > GPG agent**. The window sets how long Keyguard
remembers an approval, and the scope sets which callers can reuse it.

## Approval prompt

When a caller asks to use a key, Keyguard shows which app wants which key. A
prompt that you don't answer within 60 seconds counts as denied.

## Approval window

The **Remember key approvals** setting sets how long Keyguard remembers an
approval. The default is 5 minutes for both agents.

| Agent | Options | Platforms |
| --- | --- | --- |
| SSH agent | **Ask every time**, 1, 5 or 15 minutes, 1 or 4 hours, **Until vault locks** | Android, Linux, macOS, Windows |
| GPG agent | **Ask every time**, 1, 5 or 15 minutes, **Until vault locks** | Linux, macOS, Windows |

A remembered approval covers one key and the callers that the scope allows.
For the GPG agent, signing and decryption need separate approvals. Locking the
vault clears remembered approvals for both agents. Changing an agent's approval
settings clears its remembered approvals.

## Approval scope

The **Approval scope** setting sets which callers can reuse an approval. The
default is **Per application, isolated by terminal session**.

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

## Platform differences

| Platform | SSH agent | GPG agent |
| --- | --- | --- |
| Linux, macOS | All four scopes | All four scopes |
| Windows | Three scopes, without the terminal-isolated one. **Per application** is the default, and its approvals last while the detected app runs. | No **Approval scope** setting. An approval covers one connection. |
| Android, Termux helper | No **Approval scope** setting. An approval covers the verified app, or the current connection if Keyguard can't verify the app. | No Termux helper |
| Android, other apps | Approval windows and scopes don't apply | Approval windows and scopes don't apply |

:::caution
On Windows, process and application detection is best effort. Another program
running under your account can pass as the approved one. **Per connection**
gives the strictest isolation.
:::
