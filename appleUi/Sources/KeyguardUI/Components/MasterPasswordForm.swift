import SwiftUI
import KeyguardShared

/// Shared lifetime for the main password forms and the Quick Search unlock form.
struct MasterPasswordForm<Content: View>: View {
    @State private var model: DetailSessionModel<Bool, MasterPasswordSnapshot, MasterPasswordSession>
    @ViewBuilder let content: (MasterPasswordSnapshot, MasterPasswordActions) -> Content

    init(
        makeSession: @escaping () -> MasterPasswordSession,
        @ViewBuilder content: @escaping (MasterPasswordSnapshot, MasterPasswordActions) -> Content
    ) {
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
        self.content = content
    }

    var body: some View {
        DetailSessionContent(model: model) { snapshot, perform in
            if snapshot.loaded {
                content(
                    snapshot,
                    MasterPasswordActions(
                        setPassword: { text in perform { $0.setPassword(text: text) } },
                        setBiometric: { enabled in perform { $0.setBiometric(enabled: enabled) } },
                        setCrashlytics: { enabled in perform { $0.setCrashlytics(enabled: enabled) }
                        },
                        submit: { perform { $0.submit() } }
                    ))
            } else {
                LoadingIndicator()
            }
        }
    }
}
