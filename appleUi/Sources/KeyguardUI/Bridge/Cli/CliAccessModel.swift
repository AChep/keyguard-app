import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class CliAccessModel: SnapshotObserving {
    private let core: KeyguardCore
    private(set) var requests: [CliApprovalSnapshot] = []
    @ObservationIgnored private var observation: BridgeObservation?

    init(core: KeyguardCore) { self.core = core }

    func start() {
        startObservation(\.observation, into: \.requests, observe: core.observeCliApprovals)
    }

    func respond(id: String, approved: Bool) {
        core.resolveCliRequest(id: id, approved: approved)
    }
}
