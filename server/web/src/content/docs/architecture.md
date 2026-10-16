---
title: Architecture
description: "How Keyguard is built: the shared code, the app for each platform, the core technologies and the local-first design."
section: help
type: concept
order: 40
platforms: all
related: [security]
---

The source code of Keyguard is [available](https://github.com/AChep/keyguard-app) for inspection and is compilable with a minimal developer setup. The releases are done from publicly visible CI/CD workflows.

## Core technologies

| Part | Technology |
| --- | --- |
| Language | **Kotlin**, **Rust**, **Swift** |
| UI | **Compose** with Material 3 Expressive. **SwiftUI** in the native Apple apps. |
| Concurrency | Kotlin **coroutines** and **Flow** |
| Database | **SQLDelight** over an encrypted SQLite database, in the SQLCipher format |
| Networking | **Ktor** client |

## Modules

Most shared UI, business logic, the sync engine and the database live in
`common`. Smaller modules hold state producers without UI dependencies, and
optional features such as the Android QR scanner. Each platform app picks the
features it needs.

| Module | App |
| --- | --- |
| `androidApp` | The phone and tablet app |
| `wearApp` | The [Wear OS](/docs/wear-os/) app |
| `desktopApp` | The Linux, macOS and Windows app, running on the JVM |
| `iosApp` | The native iPhone and iPad app |
| `macosApp` | The native macOS app |

Utility modules under `util/*/` provide independent, granular libraries.
The exception is `util/ffi/`, the shared native-bridge leaf.

Integration modules under `integration/*/` implement semi-independent projects for testing Keyguard.
For example, an Android Credential provider app can test Keyguard's own implementation.

Server modules under `server/*/` implement companion projects for Keyguard, such as a website for documentation or
the license server.
