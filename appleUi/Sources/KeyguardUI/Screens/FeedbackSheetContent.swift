import SwiftUI
import KeyguardShared

struct FeedbackSheetContent: View {
    let snapshot: FeedbackSnapshot
    let setMessage: (String) -> Void
    let submit: () -> Void

    @State private var draft = ""

    var body: some View {
        ModalSheet(
            title: L10n.contactusHeaderTitle,
            width: 460,
            height: 420,
            detents: [.large]
        ) {
            FeedbackFormContent(snapshot: snapshot, draft: $draft) {
                setMessage($0)
            }
            .formStyle(.grouped)
            .disabled(!snapshot.loaded)
        } actions: {
            Button {
                submit()
            } label: {
                Label(L10n.send, systemImage: "paperplane")
            }
            .keyboardShortcut(.defaultAction)
            .disabled(!snapshot.canSend)
        }
    }
}
