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
    private(set) var gpgAgentFilters = GpgAgentFiltersSnapshot.companion.empty

    @ObservationIgnored private var requestsSubscription: BridgeObservation?
    @ObservationIgnored private var statusSubscription: BridgeObservation?
    @ObservationIgnored private var settingsSubscription: BridgeObservation?
    @ObservationIgnored private var historySubscription: BridgeObservation?
    @ObservationIgnored private var filtersSubscription: BridgeObservation?
    @ObservationIgnored private let settingsObservation = SharedObservation()
    @ObservationIgnored private let historyObservation = SharedObservation()
    @ObservationIgnored private var filterObservers: [UUID: () -> Void] = [:]

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
            startObservation(\.settingsSubscription, into: \.gpgAgentSettings, observe: core.observeGpgAgentSettings)
            return BridgeObservation { [weak self] in
                self?.stopObservation(
                    \.settingsSubscription, resetting: \.gpgAgentSettings, to: GpgAgentSettingsSnapshot.companion.empty)
            }
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

    func startGpgAgentFiltersObservation(onClose: @escaping () -> Void) -> UUID {
        let observer = UUID()
        filterObservers[observer] = onClose
        startObservation(\.filtersSubscription) { deliver in
            BridgeObservation(
                core.observeGpgAgentFilters(
                    onChange: { snapshot in
                        deliver { $0.gpgAgentFilters = snapshot }
                    },
                    onClose: {
                        deliver { model in
                            for close in Array(model.filterObservers.values) { close() }
                        }
                    }
                ))
        }
        return observer
    }

    func stopGpgAgentFiltersObservation(id: UUID) {
        filterObservers.removeValue(forKey: id)
        guard filterObservers.isEmpty else { return }
        stopObservation(
            \.filtersSubscription, resetting: \.gpgAgentFilters, to: GpgAgentFiltersSnapshot.companion.empty)
    }

    func invokeGpgAgentFilter(id: String) { core.invokeGpgAgentFilter(id: id) }
    func saveGpgAgentFilters() { core.saveGpgAgentFilters() }
    func resetGpgAgentFilters() { core.resetGpgAgentFilters() }

    func resolveGpgAgentRequest(id: String, approved: Bool) {
        core.resolveGpgAgentRequest(id: id, approved: approved)
    }

    func startGpgAgentHistoryObservation() {
        historyObservation.acquire {
            startObservation(\.historySubscription, into: \.gpgAgentHistory, observe: core.observeGpgAgentHistory)
            return BridgeObservation { [weak self] in
                self?.stopObservation(
                    \.historySubscription, resetting: \.gpgAgentHistory, to: GpgAgentHistorySnapshot.companion.empty)
            }
        }
    }

    func stopGpgAgentHistoryObservation() {
        historyObservation.release()
    }

    /// The history view obtains confirmation before invoking this action.
    func clearGpgAgentHistory() { core.clearGpgAgentHistory() }
}
