import KeyguardShared

@MainActor
protocol KeyguardPairingSource: AnyObject {
    func subscribe(
        onChange: @escaping (KeyguardPairingSnapshot) -> Void,
        onClose: @escaping () -> Void
    ) -> BridgeObservation
    func close()
    func setCode(text: String)
    func submitCode()
    func submitScannedCode(text: String)
    func newCode()
    func copyCode()
    func setDigits(text: String)
    func submitDigits()
    func approve()
    func reject()
    func retry()
    func addAnother()
}

extension KeyguardPairingSession: KeyguardPairingSource {
    func subscribe(
        onChange: @escaping (KeyguardPairingSnapshot) -> Void,
        onClose: @escaping () -> Void
    ) -> BridgeObservation {
        BridgeObservation(observe(onChange: onChange, onClose: onClose))
    }

    // Kotlin/Native exports `new…` and `copy…` names with a `do` prefix.
    func newCode() { doNewCode() }

    func copyCode() { doCopyCode() }
}
