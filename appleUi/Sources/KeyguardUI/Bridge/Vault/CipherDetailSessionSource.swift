import KeyguardShared

/// The session boundary permits observation tests without opening a vault.
@MainActor
protocol CipherDetailSessionSource: AnyObject {
    func subscribe(
        onChange: @escaping (VaultDetailSnapshot) -> Void,
        onTotpChange: @escaping (VaultDetailTotpSnapshot?) -> Void
    ) -> BridgeObservation
    func invokeAction(id: String)
    func toggleFavorite()
}

extension CipherDetailSession: CipherDetailSessionSource {
    func subscribe(
        onChange: @escaping (VaultDetailSnapshot) -> Void,
        onTotpChange: @escaping (VaultDetailTotpSnapshot?) -> Void
    ) -> BridgeObservation {
        BridgeObservation(observe(onChange: onChange, onTotpChange: onTotpChange))
    }
}
