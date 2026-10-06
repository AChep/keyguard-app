import Foundation
import Observation
import KeyguardShared

/// One process-wide agent; screen subscriptions are shared by all open windows.
@MainActor
@Observable
final class GpgAgentModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    private(set) var gpgAgentRequests: [GpgAgentRequestSnapshot] = []
    private(set) var gpgAgentStatus = GpgAgentStatusSnapshot.companion.empty
    private(set) var gpgAgentSettings = GpgAgentSettingsSnapshot.companion.empty
    private(set) var gpgAgentHistory = GpgAgentHistorySnapshot.companion.empty

    @ObservationIgnored private var requestsSubscription: BridgeObservation?
    @ObservationIgnored private var statusSubscription: BridgeObservation?
    @ObservationIgnored private var settingsSubscription: BridgeObservation?
    @ObservationIgnored private var historySubscription: BridgeObservation?
    @ObservationIgnored private let settingsObservation = SharedObservation()
    @ObservationIgnored private let historyObservation = SharedObservation()

    func startGpgAgentObservation() {
        startObservation(\.requestsSubscription, into: \.gpgAgentRequests, observe: core.observeGpgAgentRequests)
        startObservation(\.statusSubscription, into: \.gpgAgentStatus, observe: core.observeGpgAgentStatus)
    }

    func setGpgAgentEnabled(_ value: Bool) {
        core.setGpgAgentEnabled(value: value)
    }

    func retryGpgAgent() {
        core.retryGpgAgent()
    }

    func startGpgAgentSettingsObservation() {
        settingsObservation.acquire {
            sharedSnapshotObservation(
                \.settingsSubscription, into: \.gpgAgentSettings, empty: GpgAgentSettingsSnapshot.companion.empty,
                observe: core.observeGpgAgentSettings)
        }
    }

    func stopGpgAgentSettingsObservation() {
        settingsObservation.release()
    }

    func setGpgAgentApprovalWindow(optionId: String) {
        core.setGpgAgentApprovalWindow(optionId: optionId)
    }

    func setGpgAgentApprovalCachePolicy(optionId: String) {
        core.setGpgAgentApprovalCachePolicy(optionId: optionId)
    }

    func setGpgAgentDisplayKeyNames(_ value: Bool) {
        core.setGpgAgentDisplayKeyNames(value: value)
    }

    func makeFiltersSession() -> AgentFiltersSession {
        core.makeGpgAgentFiltersSession()
    }

    func resolveGpgAgentRequest(id: String, approved: Bool) {
        core.resolveGpgAgentRequest(id: id, approved: approved)
    }

    func startGpgAgentHistoryObservation() {
        historyObservation.acquire {
            sharedSnapshotObservation(
                \.historySubscription, into: \.gpgAgentHistory, empty: GpgAgentHistorySnapshot.companion.empty,
                observe: core.observeGpgAgentHistory)
        }
    }

    func stopGpgAgentHistoryObservation() {
        historyObservation.release()
    }

    /// The history view obtains confirmation before invoking this action.
    func clearGpgAgentHistory() { core.clearGpgAgentHistory() }
}
