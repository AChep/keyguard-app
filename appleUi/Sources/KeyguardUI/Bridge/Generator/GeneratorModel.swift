import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class GeneratorModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// Only live while the generator screen is on screen.
    private(set) var generator: GeneratorSnapshot = GeneratorSnapshot.companion.empty

    #if os(macOS)
    private(set) var generatorOptionsToolbar = GeneratorOptionsToolbarState.empty
    #endif

    @ObservationIgnored private var generatorSubscription: BridgeObservation?

    /// Call when the generator screen appears; balance with `stopGeneratorObservation()`.
    func startGeneratorObservation() {
        startObservation(\.generatorSubscription) { deliver in
            BridgeObservation(
                core.observeGenerator { snapshot in
                    deliver { $0.setGeneratorSnapshot(snapshot) }
                })
        }
    }

    func stopGeneratorObservation() {
        stopObservation(\.generatorSubscription)
        setGeneratorSnapshot(GeneratorSnapshot.companion.empty)
    }

    private func setGeneratorSnapshot(_ snapshot: GeneratorSnapshot) {
        generator = snapshot
        #if os(macOS)
        generatorOptionsToolbar = GeneratorOptionsToolbarState(snapshot: snapshot)
        #endif
    }

    func invokeGeneratorAction(id: String) {
        core.invokeGeneratorAction(id: id)
    }

    func setGeneratorSwitch(key: String, value: Bool) {
        core.setGeneratorSwitch(key: key, value: value)
    }

    func setGeneratorText(key: String, text: String) {
        core.setGeneratorText(key: key, text: text)
    }

    func setGeneratorCounter(key: String, value: Int32) {
        core.setGeneratorCounter(key: key, value: value)
    }

    func setGeneratorLength(_ value: Int32) {
        core.setGeneratorLength(value: value)
    }
}
