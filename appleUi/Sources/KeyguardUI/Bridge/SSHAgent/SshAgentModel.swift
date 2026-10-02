import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class SshAgentModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// Pending per-sign approval requests.
    private(set) var sshAgentRequests: [SshAgentRequestSnapshot] = []

    private(set) var sshAgentStatus: SshAgentStatusSnapshot = SshAgentStatusSnapshot.companion.empty

    /// Only live while the Developer settings screen is on screen.
    private(set) var sshAgentSettings: SshAgentSettingsSnapshot = SshAgentSettingsSnapshot.companion.empty

    @ObservationIgnored private var sshRequestsSubscription: BridgeObservation?

    @ObservationIgnored private var sshStatusSubscription: BridgeObservation?

    @ObservationIgnored private let sshAgentSettingsObservation = SharedObservation()

    @ObservationIgnored private var sshAgentSettingsSubscription: BridgeObservation?

    /// Call once so approval prompts can be shown.
    func startSshAgentObservation() {
        startObservation(\.sshRequestsSubscription, into: \.sshAgentRequests, observe: core.observeSshAgentRequests)
        startObservation(\.sshStatusSubscription, into: \.sshAgentStatus, observe: core.observeSshAgentStatus)
    }

    /// Persists the shared "SSH agent" preference; an app-level applier starts or stops
    /// the agent in response.
    func setSshAgentEnabled(_ value: Bool) {
        core.setSshAgentEnabled(value: value)
    }

    /// Call when the Developer settings screen appears; balance with
    /// `stopSshAgentSettingsObservation()`.
    func startSshAgentSettingsObservation() {
        sshAgentSettingsObservation.acquire {
            sharedSnapshotObservation(
                \.sshAgentSettingsSubscription, into: \.sshAgentSettings,
                empty: SshAgentSettingsSnapshot.companion.empty,
                observe: core.observeSshAgentSettings)
        }
    }

    func stopSshAgentSettingsObservation() {
        sshAgentSettingsObservation.release()
    }

    func setSshAgentApprovalWindow(optionId: String) {
        core.setSshAgentApprovalWindow(optionId: optionId)
    }

    func setSshAgentDisplayKeyNames(_ value: Bool) {
        core.setSshAgentDisplayKeyNames(value: value)
    }

    func makeFiltersSession() -> AgentFiltersSession {
        core.makeSshAgentFiltersSession()
    }

    func resolveSshAgentRequest(id: String, approved: Bool) {
        core.resolveSshAgentRequest(id: id, approved: approved)
    }

    func makeHistorySession(target: SshAgentHistoryTarget) -> SshAgentHistorySession {
        core.makeSshAgentHistorySession(cipherId: target.cipherId)
    }
}
