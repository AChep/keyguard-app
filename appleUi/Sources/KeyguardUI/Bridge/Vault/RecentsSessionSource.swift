import Foundation
import KeyguardShared

@MainActor
protocol RecentsSessionSource: AnyObject {
    func subscribe(
        onListDelta: @escaping (VaultDelta) -> Void,
        onTabs: @escaping (RecentsTabsSnapshot) -> Void,
        onTotp: @escaping ([String: TotpFieldSnapshot]) -> Void
    ) -> BridgeObservation
    func setTab(key: String)
}

extension RecentsSession: RecentsSessionSource {
    func subscribe(
        onListDelta: @escaping (VaultDelta) -> Void,
        onTabs: @escaping (RecentsTabsSnapshot) -> Void,
        onTotp: @escaping ([String: TotpFieldSnapshot]) -> Void
    ) -> BridgeObservation {
        BridgeObservation(
            observe(
                onListDelta: { delta in
                    assert(!Thread.isMainThread, "Recents list conversion must run off-main")
                    onListDelta(VaultDelta(bridged: delta))
                },
                onTabs: onTabs,
                onTotp: onTotp
            ))
    }
}
