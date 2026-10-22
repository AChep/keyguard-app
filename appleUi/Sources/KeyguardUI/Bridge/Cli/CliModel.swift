import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class CliModel: SnapshotObserving {
    let session: CliSession
    private(set) var snapshot: CliSnapshot?
    @ObservationIgnored private var observation: BridgeObservation?

    init(session: CliSession) {
        self.session = session
        startObservation(\.observation, into: \.snapshot, observe: { session.observe(onChange: $0) })
    }

    /// Idempotent: the session ignores a repeated close.
    func close() {
        stopObservation(\.observation, resetting: \.snapshot, to: nil)
        session.close()
    }
}
