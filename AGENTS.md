# Keyguard

Keyguard is a multi-platform password manager that maintains a local encrypted vault
and syncs it with other platforms: Bitwarden, KeePass (KDBX).

## Structure

The app is written using Kotlin Multiplatform + Compose Multiplatform + native Rust modules. We are using Gradle as the build system.

Kotlin is preferred for implementing common features. If a feature requires a large platform-dependent surface, then a native Rust module is preferred. For small one-function cases we can use Kotlin's `actual`/`expect`.

_Rust is preferred over use of JNA for new code._

### Modules

Main modules for different platforms:
- `androidApp/` native target for Android;
- `wearApp/` native target for Wear OS;
- `desktopApp/` JVM target for desktop platforms: Linux, Windows and macOS;
- `iosApp/` native target for iOS (dev).

Utility modules each implement a library we wish existed; the modules are independent and granular.

Integration modules implement projects that are useful for testing Keyguard. For example,
it's a good idea to put a test implementation of Android Credential provider app that we
can later use to test the Keyguard's own implementation.

Server modules implement companion projects for Keyguard, such as a website for documentation or
the implementation of the license server.

## Development

Optimize for safe, incremental, reviewable changes. Keep edits tightly scoped to the user request, avoid feature creeping. Simpler solutions are usually better. Code re-use is important. Preserve existing architecture and naming patterns inside each feature area.

### Check every surface

The most common defect is a change that works on the path you tested and is missing everywhere else.
Before starting the work, here's a minimal list to keep in mind:

- **Platforms.** Features usually target all platforms, unless specified otherwise. A change should not accidentally break any other platform.
- **Environments.** Platforms might also have multiple environments. For example the Linux Flatpak build might need specific handling of the Flatpak sandbox. The CI/CD environment might lack some tools this machine has.
- **Localization.** Do not hardcode strings. Do not overly avoid plurals. Use placeholders when needed.
- **Docs.** The documentation at `server/web/` should stay up to date. Do not automatically add new sections or pages, unless specifically asked to do so. Only automatically fix the now outdated info.
- **Delivery.** Changes in the build process usually affect CI/CD scripts.

### Localization

We only edit the base translations directly in the repo, as the localization of the app is managed
by a Crowdin localization platform.

### Documentation

We have a website at `server/web/` that hosts documentation relevant to a user of Keyguard. The Keyguard's users are technical and do not want to read overly verbose text:
- Say what it does, not how it feels.
- Shorten or split dense sentences. Less is better.
- Cut adverbs, or use a stronger verb.
- Prefer simpler terms.

### High-level architecture

#### Dependency injection

Platform entrypoints bootstrap DI and platform services:

- Android app bootstrap: `androidApp/src/main/java/com/artemchep/keyguard/Main.kt`
- Desktop app bootstrap: `desktopApp/src/jvmMain/kotlin/com/artemchep/keyguard/Main.kt`
- there are also common shared DI entrypoints.

Note that there are fundamentally two different layers of the dependencies. One is global and is available in every moment and the second is tied to the vault's lifecycle and only available when the vault is unlocked.

#### Navigation + screens

- Navigation is route-driven (`Route`, `NavigationIntent`, `NavigationNode`) and hosts screen content in a stack-based router.
- Screen state is usually produced via `produceScreenState(...)`, which connects a feature state flow to navigation lifecycle and persisted screen state.
- `RememberScreenStateFlow` and `RememberStateFlowScopeImpl` provide:
    - lifecycle-aware flow sharing,
    - persisted screen fields (in-memory + disk-backed),
    - scoped side effects (navigation, messaging, background actions).

For reference, here are the files for the **feedback feature**:

- Screen: `common/src/commonMain/kotlin/com/artemchep/keyguard/feature/feedback/FeedbackScreen.kt`
- State: `common/src/commonMain/kotlin/com/artemchep/keyguard/feature/feedback/FeedbackState.kt`
- State producer: `common/src/commonMain/kotlin/com/artemchep/keyguard/feature/feedback/FeedbackStateProducer.kt`

Practical rule: for new screens, follow the same split:

- `XxxScreen.kt` for rendering,
- `XxxState.kt` for UI contract,
- `XxxStateProducer.kt` for state composition, persistence, and side effects.
