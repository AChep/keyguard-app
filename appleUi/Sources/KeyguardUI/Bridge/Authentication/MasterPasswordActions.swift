import Foundation

struct MasterPasswordActions: Sendable {
    let setPassword: @MainActor @Sendable (String) -> Void
    let setBiometric: @MainActor @Sendable (Bool) -> Void
    let setCrashlytics: @MainActor @Sendable (Bool) -> Void
    /// The key derivation of a new vault.
    let kdf: MasterKdfPerform
    let setKdfExpanded: @MainActor @Sendable (Bool) -> Void
    let submit: @MainActor @Sendable () -> Void
}
