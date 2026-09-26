---
title: Architecture
description: A high-level overview of how Keyguard is built.
category: help
order: 2
---

A high-level overview of how Keyguard is built: the core technologies and how
the parts of the app interact. For the details, see the
[source code](https://github.com/AChep/keyguard-app).

## Architecture

Keyguard is a **Kotlin Multiplatform** project. Most shared UI, business logic,
the sync engine, and the database live in `common`. Smaller modules contain
state producers without UI dependencies and optional features, such as the
Android QR scanner. Each platform app selects the features it needs:

- `androidApp` — the phone and tablet app;
- `wearApp` — the [Wear OS](/docs/wear-os/) companion;
- `desktopApp` — the Linux, macOS and Windows app, running on the JVM.

A few focused modules support them: the Android autofill integration, the
[SSH agent](/docs/ssh-agent/) transports for Android and desktop, a small
native library for desktop integration, and protocol helpers such as
SignalR (live sync notifications) and WebDAV (used by
[backups](/docs/backups/)).

## Core technologies

| Part | Technology                                                     |
| --- |----------------------------------------------------------------|
| Language | **Kotlin**, **Rust**                                           |
| UI | **Compose** with Material 3 Expressive                         |
| Concurrency | Kotlin **coroutines** and **Flow**                             |
| Database | **SQLDelight** over an encrypted SQLite (SQLCipher on Android) |
| Networking | **Ktor** client                                                |
| Serialization | **kotlinx.serialization**                                      |

The phone and desktop apps share both the logic and the 
Compose screens; platform code only bridges platform features such as biometrics
and autofill.

## Local-first by design

Keyguard renders all data from the local encrypted database:

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
