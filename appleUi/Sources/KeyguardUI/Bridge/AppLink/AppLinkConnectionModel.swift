import Foundation
import Observation
import KeyguardShared

/// How this device reaches its linked apps; the same for every link. Saving closes
/// the screen.
@MainActor
@Observable
final class AppLinkConnectionModel: SnapshotObserving, KeyguardNetworkEditing {
    private let makeSource: () -> any AppLinkConnectionSource
    @ObservationIgnored private var source: (any AppLinkConnectionSource)?
    @ObservationIgnored private var subscription: BridgeObservation?

    init(makeSource: @escaping () -> any AppLinkConnectionSource) {
        self.makeSource = makeSource
    }

    private(set) var snapshot = AppLinkConnectionSnapshot.companion.empty

    /// Set once the settings are saved, or the vault locks; the screen then closes.
    private(set) var didComplete = false

    /// Call when the screen appears; balance with `stop()`.
    func start() {
        guard source == nil else { return }
        let source = makeSource()
        self.source = source
        startObservation(\.subscription) { deliver in
            source.subscribe(
                onChange: { snapshot in deliver { $0.snapshot = snapshot } },
                onClose: { deliver { $0.complete() } }
            )
        }
    }

    /// Ending the subscription drops every callback still queued behind the completion.
    private func complete() {
        stopObservation(\.subscription)
        didComplete = true
    }

    func stop() {
        stopObservation(\.subscription)
        source?.close()
        source = nil
        snapshot = AppLinkConnectionSnapshot.companion.empty
        didComplete = false
    }

    var network: KeyguardNetworkSnapshot { snapshot.network }

    func setNetworkField(id: String, text: String) {
        source?.setNetworkField(id: id, text: text)
    }

    func setAllowLan(value: Bool) {
        source?.setAllowLan(value: value)
    }

    func setRelayStorage(value: Bool) {
        source?.setRelayStorage(value: value)
    }

    func save() {
        source?.save()
    }
}
