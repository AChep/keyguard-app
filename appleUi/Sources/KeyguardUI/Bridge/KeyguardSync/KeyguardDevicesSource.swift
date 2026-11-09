import KeyguardShared

@MainActor
protocol KeyguardDevicesSource: AnyObject {
    func subscribe(onChange: @escaping (KeyguardDevicesSnapshot) -> Void) -> BridgeObservation
    func close()
    func remove(id: String)
    func leave()
}

extension KeyguardDevicesSession: KeyguardDevicesSource {
    func subscribe(onChange: @escaping (KeyguardDevicesSnapshot) -> Void) -> BridgeObservation {
        BridgeObservation(observe(onChange: onChange))
    }
}
