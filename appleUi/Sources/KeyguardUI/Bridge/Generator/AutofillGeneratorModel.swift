import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class AutofillGeneratorModel: SnapshotObserving {
    typealias Observer = (Bool, Bool, [String], @escaping (GeneratorSnapshot) -> Void) -> BridgeObservation

    let actions: GeneratorActions
    private let observeGenerator: Observer

    convenience init(session: AddFormSession) {
        self.init(
            actions: GeneratorActions(
                invoke: { session.invokeAutofillGeneratorAction(id: $0) },
                setSwitch: { session.setAutofillGeneratorSwitch(key: $0, value: $1) },
                setCounter: { session.setAutofillGeneratorCounter(key: $0, value: $1) },
                setText: { session.setAutofillGeneratorText(key: $0, text: $1) },
                setLength: { session.setAutofillGeneratorLength(value: $0) }
            ),
            observeGenerator: {
                BridgeObservation(session.observeAutofillGenerator(username: $0, password: $1, uris: $2, onChange: $3))
            }
        )
    }

    init(
        actions: GeneratorActions,
        observeGenerator: @escaping Observer
    ) {
        self.actions = actions
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

}
