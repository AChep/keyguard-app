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

    /// Generator screen state, produced by the shared Kotlin
    /// `generatorStateProducer` running headless inside `KeyguardCore`. Only live
    /// while the generator screen is on screen.
    private(set) var generator: GeneratorSnapshot = GeneratorSnapshot.companion.empty

    #if os(macOS)
    private(set) var generatorOptionsToolbar = GeneratorOptionsToolbarState.empty
    #endif

    @ObservationIgnored private var generatorSubscription: BridgeObservation?

    /// Starts running the shared generator producer. Call when the generator
    /// screen appears; balance with `stopGeneratorObservation()` on disappear.
    func startGeneratorObservation() {
        guard generatorSubscription == nil else { return }
        generatorSubscription = BridgeObservation(
            core.observeGenerator { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.setGeneratorSnapshot(snapshot)
                }
            })
    }

    func stopGeneratorObservation() {
        generatorSubscription?.cancel()
        generatorSubscription = nil
        setGeneratorSnapshot(GeneratorSnapshot.companion.empty)
    }

    private func setGeneratorSnapshot(_ snapshot: GeneratorSnapshot) {
        generator = snapshot
        #if os(macOS)
        assignIfChanged(&generatorOptionsToolbar, GeneratorOptionsToolbarState(snapshot: snapshot))
        #endif
    }

    /// Invokes a generator `() -> Unit` closure (type select / copy / refresh /
    /// suggestion / enum option / menu) by its opaque snapshot id.
    func invokeGeneratorAction(id: String) {
        core.invokeGeneratorAction(id: id)
    }

    /// Sets a boolean generator filter switch identified by its filter key.
    func setGeneratorSwitch(key: String, value: Bool) {
        core.setGeneratorSwitch(key: key, value: value)
    }

    /// Writes text into a generator text filter identified by its filter key.
    func setGeneratorText(key: String, text: String) {
        core.setGeneratorText(key: key, text: text)
    }

    /// Sets an integer generator counter identified by its routing key.
    func setGeneratorCounter(key: String, value: Int32) {
        core.setGeneratorCounter(key: key, value: value)
    }

    /// Sets the generated value length.
    func setGeneratorLength(_ value: Int32) {
        core.setGeneratorLength(value: value)
    }
}
