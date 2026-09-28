import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class AddKeyGeneratorModel: SnapshotObserving {
    typealias Observer = (String, String, @escaping (AddKeyGeneratorSnapshot) -> Void) -> BridgeObservation

    let itemId: String
    let isGpg: Bool
    private let coreProvider: () -> KeyguardCore
    private let observe: Observer
    private let apply: (String) -> Bool
    private let sessionId = UUID().uuidString

    private(set) var snapshot = AddKeyGeneratorSnapshot.companion.empty
    @ObservationIgnored private var subscription: BridgeObservation?

    convenience init(core: KeyguardCore, itemId: String, isGpg: Bool) {
        self.init(
            itemId: itemId,
            isGpg: isGpg,
            coreProvider: { core },
            observe: { BridgeObservation(core.observeAddKeyGenerator(itemId: $0, sessionId: $1, onChange: $2)) },
            apply: { core.useAddGeneratedKey(sessionId: $0) }
        )
    }

    init(
        itemId: String,
        isGpg: Bool,
        coreProvider: @escaping () -> KeyguardCore,
        observe: @escaping Observer,
        apply: @escaping (String) -> Bool
    ) {
        self.itemId = itemId
        self.isGpg = isGpg
        self.coreProvider = coreProvider
        self.observe = observe
        self.apply = apply
    }

    var title: String { isGpg ? L10n.generatorHeaderGpgKeyTitle : L10n.generatorHeaderSshKeyTitle }

    var actions: GeneratorActions {
        GeneratorActions(
            invoke: { self.coreProvider().invokeAddKeyGeneratorAction(sessionId: self.sessionId, id: $0) },
            setSwitch: { self.coreProvider().setAddKeyGeneratorSwitch(sessionId: self.sessionId, key: $0, value: $1) },
            setCounter: {
                self.coreProvider().setAddKeyGeneratorCounter(sessionId: self.sessionId, key: $0, value: $1)
            },
            setText: { self.coreProvider().setAddKeyGeneratorText(sessionId: self.sessionId, key: $0, text: $1) },
            // Key generators have no length slider; their lengths are enum options.
            setLength: { _ in }
        )
    }

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
