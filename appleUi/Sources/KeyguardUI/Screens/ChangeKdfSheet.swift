import SwiftUI
import KeyguardShared

struct ChangeKdfSheet: View {
    @State private var model: DetailSessionModel<Bool, ChangeKdfSnapshot, ChangeKdfSession>

    init(makeSession: @escaping () -> ChangeKdfSession) {
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribeWithCompletion: { BridgeObservation($0.observe(onChange: $1, onClose: $2)) }
            ))
    }

    var body: some View {
        DetailSessionContent(
            model: model, initialSnapshot: ChangeKdfSnapshot.companion.empty, dismissOnComplete: true
        ) {
            snapshot, perform in
            ChangeKdfSheetContent(
                s: snapshot,
                setPassword: { text in perform { $0.setPassword(text: text) } },
                kdf: { edit in perform { edit($0.kdf) } },
                submit: { perform { $0.submit() } }
            )
        }
    }
}
