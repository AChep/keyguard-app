import SwiftUI
import KeyguardShared

struct FeedbackView: View {
    @Environment(NavigationModel.self) private var navigationModel
    let entry: ScreenEntrySnapshot

    @State private var draft = ""

    private var snapshot: FeedbackSnapshot {
        entry.feedback ?? FeedbackSnapshot.companion.empty
    }

    var body: some View {
        FeedbackFormContent(snapshot: snapshot, draft: $draft) {
            navigationModel.setEntryFeedbackMessage(instanceId: entry.instanceId, text: $0)
        }
        .formStyle(.grouped)
        .navigationTitle(L10n.contactusHeaderTitle)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    navigationModel.submitEntryFeedback(instanceId: entry.instanceId)
                } label: {
                    Label(L10n.send, systemImage: "paperplane")
                }
                .disabled(!snapshot.canSend)
            }
        }
    }
}

struct FeedbackFormContent: View {
    let snapshot: FeedbackSnapshot
    @Binding var draft: String
    let setMessage: (String) -> Void

    var body: some View {
        Form {
            Section {
                TextField(L10n.contactusMessageLabel, text: $draft, axis: .vertical)
                    .lineLimit(4...12)
                    .bridgedText(
                        $draft,
                        remote: snapshot.message,
                        remoteRevision: snapshot.messageRevision,
                        send: setMessage
                    )
                if let error = snapshot.error, !error.isEmpty {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
            Section {
                Label {
                    Text(L10n.contactusEnglishNote).fontWeight(.semibold)
                } icon: {
                    Image(systemName: "info.circle")
                }
                Text(L10n.contactusThanksNote)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
