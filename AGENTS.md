# AGENTS.md

## Purpose & Boundaries

- Optimize for safe, incremental, reviewable changes.
- Keep edits tightly scoped to the user request. Avoid opportunistic refactors.
- Prefer shared changes in `:common` when behavior should be consistent across Android and Desktop.
- Treat `androidApp`, `desktopApp`, and release/deployment config as higher-risk surfaces; only touch them when required.
- Preserve existing architecture and naming patterns inside each feature area.

## Repository Map

- `:common`  
  Shared Kotlin Multiplatform logic, Compose UI, feature routes/screens/state/state producers, use cases, services, SQLDelight schemas.
- `:androidApp`  
  Android application module, build flavors/build types, Android packaging and platform integration.
- `:androidTest`  
  Shared Android instrumentation test code and dependencies.
- `:androidBenchmark`  
  Macrobenchmark + baseline profile generation module targeting `:androidApp`.
- `:desktopApp`  
  Desktop application entrypoint and Compose desktop packaging/distribution.
- `:desktopLibJvm`  
  JVM-side desktop support that consumes artifacts from `:desktopLibNative`.
- `:desktopLibNative`  
  Kotlin/Native shared library builds for Linux/macOS/Windows used by desktop runtime integrations.

## Environment Baseline

- Use JDK `21` to match CI setup defaults.
- Assume Kotlin Multiplatform + Compose Multiplatform project conventions.
- Do not assume Android emulator/device availability unless explicitly requested by the user.

## High-Level Architecture Overview

- Platform entrypoints bootstrap DI and platform services:
  - Android app bootstrap: `androidApp/src/main/java/com/artemchep/keyguard/Main.kt`
  - Desktop app bootstrap: `desktopApp/src/jvmMain/kotlin/com/artemchep/keyguard/Main.kt`
- Feature implementation primarily lives in `common/src/commonMain/kotlin/com/artemchep/keyguard/feature/*`.
- Navigation is route-driven (`Route`, `NavigationIntent`, `NavigationNode`) and hosts screen content in a stack-based router.
- Screen state is usually produced via `produceScreenState(...)`, which connects a feature state flow to navigation lifecycle and persisted screen state.
- `RememberScreenStateFlow` and `RememberStateFlowScopeImpl` provide:
  - lifecycle-aware flow sharing,
  - persisted screen fields (in-memory + disk-backed),
  - scoped side effects (navigation, messaging, background actions).

## Example: Screen + State + State Producer

Feedback feature reference files:

- Screen: `common/src/commonMain/kotlin/com/artemchep/keyguard/feature/feedback/FeedbackScreen.kt`
- State: `common/src/commonMain/kotlin/com/artemchep/keyguard/feature/feedback/FeedbackState.kt`
- State producer: `common/src/commonMain/kotlin/com/artemchep/keyguard/feature/feedback/FeedbackStateProducer.kt`

How they work together:

1. `FeedbackScreen()` calls `produceFeedbackScreenState()` and renders a `Loadable<FeedbackState>`.
2. `produceFeedbackScreenState()` uses `produceScreenState(key = "feedback", initial = Loadable.Loading)`.
3. Inside the producer, `mutablePersistedFlow("message", ...)` creates persisted input state for the message field.
4. The producer validates input, creates UI callbacks (`onSendClick`, `onClear`), and maps domain/navigation actions into `FeedbackState`.
5. The screen is mostly a pure renderer: it binds UI widgets to `FeedbackState` and executes callbacks from state.

Practical rule: for new screens, follow the same split:

- `XxxScreen.kt` for rendering,
- `XxxState.kt` for UI contract,
- `XxxStateProducer.kt` for state composition, persistence, and side effects.

## Change-Safety Rules

- Do not modify signing, notarization, release packaging, or deployment workflow files unless explicitly requested.
- Do not change build flavor/build type behavior (`playStore`/`none`, release variants) unless required by the task.
- Avoid cross-module moves/renames in first-pass changes; prefer local modifications.
- In handoff notes, always state:
  - which modules were changed,
  - behavior impact,
  - any unverified risk due to not running platform-specific tests.
