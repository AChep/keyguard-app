import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class AutofillGeneratorModel: SnapshotObserving {
    typealias Observer = (Bool, Bool, [String], @escaping (GeneratorSnapshot) -> Void) -> BridgeObservation

    private let coreProvider: () -> KeyguardCore
    private var core: KeyguardCore { coreProvider() }
    private let observeGenerator: Observer

    convenience init(core: KeyguardCore) {
        self.init(
            coreProvider: { core },
            observeGenerator: {
                BridgeObservation(core.observeAutofillGenerator(username: $0, password: $1, uris: $2, onChange: $3))
            }
        )
    }

    init(
        coreProvider: @escaping () -> KeyguardCore,
        observeGenerator: @escaping Observer
    ) {
        self.coreProvider = coreProvider
        self.observeGenerator = observeGenerator
    }

    private(set) var autofillGenerator: GeneratorSnapshot = GeneratorSnapshot.companion.empty

    @ObservationIgnored private var autofillGeneratorSubscription: BridgeObservation?

    func startAutofillGeneratorObservation(username: Bool, password: Bool, uris: [String]) {
        stopAutofillGeneratorObservation()
        startObservation(\.autofillGeneratorSubscription, into: \.autofillGenerator) { onChange in
            observeGenerator(username, password, uris, onChange)
        }
    }

    func stopAutofillGeneratorObservation() {
        stopObservation(
            \.autofillGeneratorSubscription, resetting: \.autofillGenerator, to: GeneratorSnapshot.companion.empty)
    }

    /// Invokes an in-form generator `() -> Unit` closure (type select / copy /
    /// refresh / suggestion / enum option / menu) by its opaque snapshot id.
    func invokeAutofillGeneratorAction(id: String) {
        core.invokeAutofillGeneratorAction(id: id)
    }

    /// Sets a boolean in-form generator filter switch identified by its filter key.
    func setAutofillGeneratorSwitch(key: String, value: Bool) {
        core.setAutofillGeneratorSwitch(key: key, value: value)
    }

    /// Writes text into an in-form generator text filter identified by its filter key.
    func setAutofillGeneratorText(key: String, text: String) {
        core.setAutofillGeneratorText(key: key, text: text)
    }

    /// Sets an integer in-form generator counter identified by its routing key.
    func setAutofillGeneratorCounter(key: String, value: Int32) {
        core.setAutofillGeneratorCounter(key: key, value: value)
    }

    /// Sets the in-form generated value length.
    func setAutofillGeneratorLength(_ value: Int32) {
        core.setAutofillGeneratorLength(value: value)
    }
}
