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

    /// Only live while the SSH agent history screen is on screen.
    private(set) var sshAgentHistory: SshAgentHistorySnapshot = SshAgentHistorySnapshot.companion.empty

    /// Only live while the Developer settings screen is on screen.
    private(set) var sshAgentSettings: SshAgentSettingsSnapshot = SshAgentSettingsSnapshot.companion.empty

    /// Only live while the SSH agent filters screen is on screen.
    private(set) var sshAgentFilters: SshAgentFiltersSnapshot = SshAgentFiltersSnapshot.companion.empty

    @ObservationIgnored private var sshRequestsSubscription: BridgeObservation?

    @ObservationIgnored private var sshStatusSubscription: BridgeObservation?

    @ObservationIgnored private var sshAgentHistorySubscription: BridgeObservation?

    @ObservationIgnored private var sshAgentSettingsSubscription: BridgeObservation?

    @ObservationIgnored private var sshAgentFiltersSubscription: BridgeObservation?

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
        startObservation(
            \.sshAgentSettingsSubscription, into: \.sshAgentSettings, observe: core.observeSshAgentSettings)
    }

    func stopSshAgentSettingsObservation() {
        stopObservation(
            \.sshAgentSettingsSubscription, resetting: \.sshAgentSettings, to: SshAgentSettingsSnapshot.companion.empty)
    }

    func setSshAgentApprovalWindow(optionId: String) {
        core.setSshAgentApprovalWindow(optionId: optionId)
    }

    func setSshAgentDisplayKeyNames(_ value: Bool) {
        core.setSshAgentDisplayKeyNames(value: value)
    }

    /// Call when the filters screen appears; balance with `stopSshAgentFiltersObservation()`.
    /// `onClose` fires when the producer pops itself after a successful save.
    func startSshAgentFiltersObservation(onClose: @escaping () -> Void) {
        startObservation(\.sshAgentFiltersSubscription) { deliver in
            BridgeObservation(
                core.observeSshAgentFilters(
                    onChange: { snapshot in
                        deliver { $0.sshAgentFilters = snapshot }
                    },
                    onClose: {
                        deliver { _ in onClose() }
                    }
                ))
        }
    }

    func stopSshAgentFiltersObservation() {
        stopObservation(
            \.sshAgentFiltersSubscription, resetting: \.sshAgentFilters, to: SshAgentFiltersSnapshot.companion.empty)
    }

    /// Toggles a filter chip (or section header) by its snapshot id.
    func invokeSshAgentFilter(id: String) {
        core.invokeSshAgentFilter(id: id)
    }

    func saveSshAgentFilters() {
        core.saveSshAgentFilters()
    }

    func resetSshAgentFilters() {
        core.resetSshAgentFilters()
    }

    func resolveSshAgentRequest(id: String, approved: Bool) {
        core.resolveSshAgentRequest(id: id, approved: approved)
    }

    /// For one cipher or (`nil`) all of them. Call when the SSH agent history screen
    /// appears; balance with `stopSshAgentHistoryObservation()`.
    func startSshAgentHistoryObservation(cipherId: String? = nil) {
        stopSshAgentHistoryObservation()
        startObservation(\.sshAgentHistorySubscription, into: \.sshAgentHistory) { onChange in
            core.observeSshAgentHistory(cipherId: cipherId, onChange: onChange)
        }
    }

    func stopSshAgentHistoryObservation() {
        stopObservation(
            \.sshAgentHistorySubscription, resetting: \.sshAgentHistory, to: SshAgentHistorySnapshot.companion.empty)
    }
}
