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

    /// Generator history list state, produced by the shared Kotlin
    /// `generatorHistoryStateProducer` running headless inside `KeyguardCore`.
    /// Only live while the generator history screen is on screen.
    private(set) var generatorHistory: GeneratorHistorySnapshot = GeneratorHistorySnapshot.companion.empty

    @ObservationIgnored private var generatorHistorySubscription: BridgeObservation?

    /// Starts running the shared generator history producer. Call when the
    /// generator history screen appears; balance with `stopGeneratorHistoryObservation()`.
    func startGeneratorHistoryObservation() {
        startObservation(
            \.generatorHistorySubscription, into: \.generatorHistory, observe: core.observeGeneratorHistory)
    }

    func stopGeneratorHistoryObservation() {
        stopObservation(
            \.generatorHistorySubscription, resetting: \.generatorHistory, to: GeneratorHistorySnapshot.companion.empty)
    }

    /// Runs a per-item dropdown action of a generator history row (copy / show in
    /// large type / check breaches / remove) by its opaque id.
    func invokeGeneratorHistoryItemAction(id: String) {
        core.invokeGeneratorHistoryItemAction(id: id)
    }

    /// Runs a top-level overflow option of the generator history (Clear history).
    func invokeGeneratorHistoryOption(id: String) {
        core.invokeGeneratorHistoryOption(id: id)
    }

    /// Runs a bulk action of the active generator history multi-selection (Remove
    /// from history) by its opaque id.
    func invokeGeneratorHistorySelectionAction(id: String) {
        core.invokeGeneratorHistorySelectionAction(id: id)
    }

    /// Toggles whether the generator history row with `id` is part of the
    /// multi-selection. Routes through the shared producer's per-item handle.
    func toggleGeneratorHistorySelection(id: String) {
        core.toggleGeneratorHistorySelection(itemId: id)
    }

    /// Clears the active generator history multi-selection.
    func clearGeneratorHistorySelection() {
        core.clearGeneratorHistorySelection()
    }

    /// Selects every generator history value row.
    func selectAllGeneratorHistory() {
        core.selectAllGeneratorHistory()
    }
}
