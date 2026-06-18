---
title: Architecture
description: A high-level overview of how Keyguard is built.
category: help
order: 4
---

A high-level overview of how Keyguard is built: the core technologies and how
the parts of the app interact. For the details, see the
[source code](https://github.com/AChep/keyguard-app).

## One codebase, every platform

Keyguard is by far a **Kotlin Multiplatform** project. The UI, the business logic,
the sync engine, and the database live in one shared module called `common`.
The apps you install are thin shells around it:

- `androidApp` — the phone and tablet app;
- `wearApp` — the [Wear OS](/docs/wear-os/) companion;
- `desktopApp` — the Linux, macOS and Windows app, running on the JVM.

A few focused modules support them: the Android autofill integration, the
[SSH agent](/docs/ssh-agent/) transports for Android and desktop, a small
native library for desktop integration, and protocol helpers such as
SignalR (live sync notifications) and WebDAV (used by
[backups](/docs/backups/)).

## Core technologies

| Part | Technology |
| --- | --- |
| Language | **Kotlin** |
| UI | **Compose** with Material 3 Expressive |
| Concurrency | Kotlin **coroutines** and **Flow** |
| Database | **SQLDelight** over an encrypted SQLite (SQLCipher on Android) |
| Networking | **Ktor** client |
| Serialization | **kotlinx.serialization** |

The phone and desktop apps share both the logic and the 
Compose screens; platform code is limited to glue such as biometrics,
autofill, etc.

## Local-first by design

Keyguard never renders straight from the network:

1. Your accounts' data is mirrored into the local encrypted database.
2. Screens read from that mirror, which is why the app works offline.
3. A **sync engine** reconciles the mirror with the source of truth: it
   downloads server changes, merges them with your pending local edits, and
   uploads what's yours. For Bitwarden accounts a WebSocket (SignalR)
   connection tells Keyguard when something changes; for
   [KeePass](/docs/keepass/) the KDBX file itself is the source of truth.

How disagreements between local and remote edits are settled is covered in
[Sync & conflict resolution](/docs/sync-and-conflicts/); how the local
database is encrypted is covered in
[Security & privacy](/docs/security/).
