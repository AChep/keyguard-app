import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class EnvironmentsModel: SnapshotObserving {
    let session: EnvironmentsSession
    private(set) var snapshot: EnvironmentsSnapshot?
    @ObservationIgnored private var observation: BridgeObservation?

    init(session: EnvironmentsSession) {
        self.session = session
        startObservation(\.observation, into: \.snapshot, observe: { session.observe(onChange: $0) })
    }

    /// Idempotent: the session ignores a repeated close.
    func close() {
        stopObservation(\.observation, resetting: \.snapshot, to: nil)
        session.close()
    }
}
