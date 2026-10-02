import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class AddKeyGeneratorModel: SnapshotObserving {
    typealias Observer = (String, String, @escaping (AddKeyGeneratorSnapshot) -> Void) -> BridgeObservation

    let itemId: String
    let isGpg: Bool
    private let actionsProvider: (String) -> GeneratorActions
    private let observe: Observer
    private let apply: (String) -> Bool
    private let sessionId = UUID().uuidString

    private(set) var snapshot = AddKeyGeneratorSnapshot.companion.empty
    @ObservationIgnored private var subscription: BridgeObservation?

    convenience init(session: AddFormSession, itemId: String, isGpg: Bool) {
        self.init(
            itemId: itemId,
            isGpg: isGpg,
            actionsProvider: { id in
                GeneratorActions(
                    invoke: { session.invokeAddKeyGeneratorAction(sessionId: id, id: $0) },
                    setSwitch: { session.setAddKeyGeneratorSwitch(sessionId: id, key: $0, value: $1) },
                    setCounter: { session.setAddKeyGeneratorCounter(sessionId: id, key: $0, value: $1) },
                    setText: { session.setAddKeyGeneratorText(sessionId: id, key: $0, text: $1) },
                    setLength: { _ in }
                )
            },
            observe: { BridgeObservation(session.observeAddKeyGenerator(itemId: $0, sessionId: $1, onChange: $2)) },
            apply: { session.useAddGeneratedKey(sessionId: $0) }
        )
    }

    init(
        itemId: String,
        isGpg: Bool,
        actionsProvider: @escaping (String) -> GeneratorActions,
        observe: @escaping Observer,
        apply: @escaping (String) -> Bool
    ) {
        self.itemId = itemId
        self.isGpg = isGpg
        self.actionsProvider = actionsProvider
        self.observe = observe
        self.apply = apply
    }

    var title: String { isGpg ? L10n.generatorHeaderGpgKeyTitle : L10n.generatorHeaderSshKeyTitle }

    var actions: GeneratorActions { actionsProvider(sessionId) }

    func start() {
        startObservation(\.subscription, into: \.snapshot) { onChange in
            observe(itemId, sessionId, onChange)
        }
    }

    func stop() {
        stopObservation(\.subscription, resetting: \.snapshot, to: AddKeyGeneratorSnapshot.companion.empty)
    }

    func useKey() -> Bool {
        guard subscription != nil, snapshot.canUseKey, apply(sessionId) else { return false }
        stop()
        return true
    }
}
