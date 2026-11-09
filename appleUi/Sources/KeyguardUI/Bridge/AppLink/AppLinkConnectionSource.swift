import KeyguardShared

@MainActor
protocol AppLinkConnectionSource: AnyObject {
    func subscribe(
        onChange: @escaping (AppLinkConnectionSnapshot) -> Void,
        onClose: @escaping () -> Void
    ) -> BridgeObservation
    func close()
    func setNetworkField(id: String, text: String)
    func setAllowLan(value: Bool)
    func setRelayStorage(value: Bool)
    func save()
}

extension AppLinkConnectionSession: AppLinkConnectionSource {
    func subscribe(
        onChange: @escaping (AppLinkConnectionSnapshot) -> Void,
        onClose: @escaping () -> Void
    ) -> BridgeObservation {
        BridgeObservation(observe(onChange: onChange, onClose: onClose))
    }
}
