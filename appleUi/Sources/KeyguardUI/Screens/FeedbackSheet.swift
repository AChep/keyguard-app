import SwiftUI
import KeyguardShared

struct FeedbackSheet: View {
    @State private var model: DetailSessionModel<Bool, FeedbackSnapshot, FeedbackSession>

    init(makeSession: @escaping () -> FeedbackSession) {
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model, initialSnapshot: FeedbackSnapshot.companion.empty) {
            snapshot, perform in
            FeedbackSheetContent(
                snapshot: snapshot,
                setMessage: { text in perform { $0.setMessage(text: text) } },
                submit: { perform { $0.submit() } }
            )
        }
    }
}
