import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class ServiceDirectoryModel: SnapshotObserving {
    typealias DetailObserver = (String, String, @escaping (ServiceDirectoryDetailSnapshot) -> Void) -> BridgeObservation

    private let observeDetail: DetailObserver

    convenience init(core: KeyguardCore) {
        self.init(
            observeDetail: { BridgeObservation(core.observeServiceDirectoryDetail(kind: $0, itemId: $1, onChange: $2)) }
        )
    }

    init(
        observeDetail: @escaping DetailObserver
    ) {
        self.observeDetail = observeDetail
    }

    /// Detail resolved by stable ID from the shared directory catalog. Only
    /// live while a directory detail screen is on screen.
    private(set) var directoryDetail: ServiceDirectoryDetailSnapshot = ServiceDirectoryDetailSnapshot.companion.empty

    @ObservationIgnored private var directoryDetailSubscription: BridgeObservation?

    /// Starts loading one directory entry. Call when
    /// a detail screen appears; balance with `stopServiceDirectoryDetailObservation`.
    func startServiceDirectoryDetailObservation(kind: String, itemId: String) {
        stopServiceDirectoryDetailObservation()
        startObservation(\.directoryDetailSubscription, into: \.directoryDetail) { onChange in
            observeDetail(kind, itemId, onChange)
        }
    }

    func stopServiceDirectoryDetailObservation() {
        stopObservation(
            \.directoryDetailSubscription, resetting: \.directoryDetail,
            to: ServiceDirectoryDetailSnapshot.companion.empty)
    }
}
