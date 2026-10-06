import SwiftUI
import KeyguardShared

struct ChangePasswordSheet: View {
    @State private var model: DetailSessionModel<Bool, ChangePasswordSnapshot, ChangePasswordSession>

    init(makeSession: @escaping () -> ChangePasswordSession) {
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribeWithCompletion: { BridgeObservation($0.observe(onChange: $1, onClose: $2)) }
            ))
    }

    var body: some View {
        DetailSessionContent(
            model: model, initialSnapshot: ChangePasswordSnapshot.companion.empty, dismissOnComplete: true
        ) {
            snapshot, perform in
            ChangePasswordSheetContent(
                s: snapshot,
                setCurrentPassword: { text in perform { $0.setCurrentPassword(text: text) } },
                setNewPassword: { text in perform { $0.setNewPassword(text: text) } },
                setBiometric: { enabled in perform { $0.setBiometric(enabled: enabled) } },
                submit: { perform { $0.submit() } }
            )
        }
    }
}
