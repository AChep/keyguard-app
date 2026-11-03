import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class EnvironmentAccessModel: SnapshotObserving {
    private let core: KeyguardCore
    private(set) var requests: [EnvironmentApprovalSnapshot] = []
    @ObservationIgnored private var observation: BridgeObservation?

    init(core: KeyguardCore) { self.core = core }

    func start() {
        startObservation(\.observation, into: \.requests, observe: core.observeEnvironmentApprovals)
    }

    func provideDirectoryGrant(path: String, bookmark: String) {
        core.provideEnvironmentDirectoryGrant(path: path, bookmark: bookmark)
    }

    func respond(id: String, approved: Bool) {
        core.resolveEnvironmentRequest(id: id, approved: approved)
    }
}
