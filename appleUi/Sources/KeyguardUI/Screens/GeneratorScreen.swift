import SwiftUI
import KeyguardShared

struct GeneratorScreen: View {
    @State private var model: DetailSessionModel<Bool, GeneratorSnapshot, GeneratorSession>

    init(makeSession: @escaping () -> GeneratorSession) {
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model) {
            snapshot, perform in
            GeneratorWorkspaceView(
                generator: snapshot,
                actions: GeneratorActions(
                    invoke: { id in perform { $0.invokeGeneratorAction(id: id) } },
                    setSwitch: { key, value in
                        perform { $0.setGeneratorSwitch(key: key, value: value) }
                    },
                    setCounter: { key, value in
                        perform { $0.setGeneratorCounter(key: key, value: value) }
                    },
                    setText: { key, text in perform { $0.setGeneratorText(key: key, text: text) } },
                    setLength: { value in perform { $0.setGeneratorLength(value: value) } }
                )
            )
        }
    }
}
