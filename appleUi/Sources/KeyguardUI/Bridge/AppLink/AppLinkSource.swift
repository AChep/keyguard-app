import KeyguardShared

@MainActor
protocol AppLinkSource: AnyObject {
    func subscribe(onChange: @escaping (AppLinkSnapshot) -> Void) -> BridgeObservation
    func close()
    func setCategory(key: String, value: Bool)
    func rename()
}

extension AppLinkSession: AppLinkSource {
    func subscribe(onChange: @escaping (AppLinkSnapshot) -> Void) -> BridgeObservation {
        BridgeObservation(observe(onChange: onChange))
    }
}
