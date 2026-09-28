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

    /// Pending SSH agent per-sign approval requests, surfaced by `KeyguardCore`.
    private(set) var sshAgentRequests: [SshAgentRequestSnapshot] = []

    /// SSH agent running status + advertised SSH_AUTH_SOCK path.
    private(set) var sshAgentStatus: SshAgentStatusSnapshot = SshAgentStatusSnapshot.companion.empty

    /// SSH agent signing history list state, produced by the shared Kotlin
    /// `sshAgentHistoryStateProducer` running headless inside `KeyguardCore`. Only
    /// live while the SSH agent history screen is on screen.
    private(set) var sshAgentHistory: SshAgentHistorySnapshot = SshAgentHistorySnapshot.companion.empty

    /// SSH agent preferences (the Developer pane), produced by the shared Kotlin
    /// SSH agent settings use cases running inside `KeyguardCore`. Only live
    /// while the Developer settings screen is on screen.
    private(set) var sshAgentSettings: SshAgentSettingsSnapshot = SshAgentSettingsSnapshot.companion.empty

    /// SSH agent filters screen state, produced by the shared Kotlin
    /// `sshAgentFiltersStateProducer` running headless inside `KeyguardCore`.
    /// Only live while the SSH agent filters screen is on screen.
    private(set) var sshAgentFilters: SshAgentFiltersSnapshot = SshAgentFiltersSnapshot.companion.empty

    @ObservationIgnored private var sshRequestsSubscription: BridgeObservation?

    @ObservationIgnored private var sshStatusSubscription: BridgeObservation?

    @ObservationIgnored private var sshAgentHistorySubscription: BridgeObservation?

    @ObservationIgnored private var sshAgentSettingsSubscription: BridgeObservation?

    @ObservationIgnored private var sshAgentFiltersSubscription: BridgeObservation?

    /// Starts observing SSH agent approval requests + status. Call once (e.g. on
    /// the main window appearing) so approval prompts can be shown.
    func startSshAgentObservation() {
        if sshRequestsSubscription == nil {
            sshRequestsSubscription = BridgeObservation(
                core.observeSshAgentRequests { [weak self] requests in
                    Task { @MainActor [weak self] in
                        self?.sshAgentRequests = requests
                    }
                })
        }
        if sshStatusSubscription == nil {
            sshStatusSubscription = BridgeObservation(
                core.observeSshAgentStatus { [weak self] status in
                    Task { @MainActor [weak self] in
                        self?.sshAgentStatus = status
                    }
                })
        }
    }

    func stopSshAgentObservation() {
        sshRequestsSubscription?.cancel()
        sshRequestsSubscription = nil
        sshStatusSubscription?.cancel()
        sshStatusSubscription = nil
    }

    /// Persists the shared "SSH agent" preference; the applier started in
    /// `init` starts or stops the agent in response.
    func setSshAgentEnabled(_ value: Bool) {
        core.setSshAgentEnabled(value: value)
    }

    /// Starts running the shared SSH agent settings use cases. Call when the
    /// Developer settings screen appears; balance with
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

    /// Starts running the shared SSH agent filters producer. Call when the
    /// filters screen appears; balance with `stopSshAgentFiltersObservation()`.
    /// `onClose` fires when the producer pops itself after a successful save.
    func startSshAgentFiltersObservation(onClose: @escaping () -> Void) {
        guard sshAgentFiltersSubscription == nil else { return }
        sshAgentFiltersSubscription = BridgeObservation(
            core.observeSshAgentFilters(
                onChange: { [weak self] snapshot in
                    Task { @MainActor [weak self] in
                        self?.sshAgentFilters = snapshot
                    }
                },
                onClose: {
                    Task { @MainActor in
                        onClose()
                    }
                }
            ))
    }

    func stopSshAgentFiltersObservation() {
        sshAgentFiltersSubscription?.cancel()
        sshAgentFiltersSubscription = nil
        sshAgentFilters = SshAgentFiltersSnapshot.companion.empty
    }

    /// Toggles a filter chip (or section header) by its snapshot id.
    func invokeSshAgentFilter(id: String) {
        core.invokeSshAgentFilter(id: id)
    }

    /// Persists the pending filter; the producer pops itself on success.
    func saveSshAgentFilters() {
        core.saveSshAgentFilters()
    }

    /// Clears the pending filter selection.
    func resetSshAgentFilters() {
        core.resetSshAgentFilters()
    }

    /// Approves (or denies) a pending SSH sign request by its id.
    func resolveSshAgentRequest(id: String, approved: Bool) {
        core.resolveSshAgentRequest(id: id, approved: approved)
    }

    /// Starts running the shared SSH agent history producer, for one cipher or (`nil`)
    /// all of them. Call when the SSH agent history screen appears; balance with
    /// `stopSshAgentHistoryObservation()`.
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
