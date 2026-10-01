import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class GeneratorHistoryModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// Only live while the generator history screen is on screen.
    private(set) var generatorHistory: GeneratorHistorySnapshot = GeneratorHistorySnapshot.companion.empty

    @ObservationIgnored private var generatorHistorySubscription: BridgeObservation?

    /// Call when the generator history screen appears; balance with
    /// `stopGeneratorHistoryObservation()`.
    func startGeneratorHistoryObservation() {
        startObservation(
            \.generatorHistorySubscription, into: \.generatorHistory, observe: core.observeGeneratorHistory)
    }

    func stopGeneratorHistoryObservation() {
        stopObservation(
            \.generatorHistorySubscription, resetting: \.generatorHistory, to: GeneratorHistorySnapshot.companion.empty)
    }

    func invokeGeneratorHistoryItemAction(id: String) {
        core.invokeGeneratorHistoryItemAction(id: id)
    }

    /// Runs a top-level overflow option ("Clear history").
    func invokeGeneratorHistoryOption(id: String) {
        core.invokeGeneratorHistoryOption(id: id)
    }

    func invokeGeneratorHistorySelectionAction(id: String) {
        core.invokeGeneratorHistorySelectionAction(id: id)
    }

    func toggleGeneratorHistorySelection(id: String) {
        core.toggleGeneratorHistorySelection(itemId: id)
    }

    func clearGeneratorHistorySelection() {
        core.clearGeneratorHistorySelection()
    }
}
