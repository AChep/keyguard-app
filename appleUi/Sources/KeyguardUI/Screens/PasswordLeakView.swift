import SwiftUI
import KeyguardShared

struct PasswordLeakView: View {
    @Environment(DialogsModel.self) private var dialogsModel

    var body: some View {
        ModalSheet(
            title: dialogsModel.passwordLeak?.title ?? "",
            width: 460,
            height: 420,
            detents: [.medium, .large]
        ) {
            if let snapshot = dialogsModel.passwordLeak {
                content(snapshot)
            } else {
                // Briefly nil mid-dismissal; render nothing.
                Color.clear
            }
        }
    }

    @ViewBuilder
    private func content(_ snapshot: PasswordLeakSnapshot) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(snapshot.note)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if snapshot.isLoading {
                    LeakLoadingSkeleton()
                } else if let errorText = snapshot.errorText {
                    LeakNote(kind: .warning, text: errorText)
                } else if let occurrences = snapshot.occurrences?.intValue, occurrences > 0 {
                    LeakNote(
                        kind: .warning,
                        title: snapshot.occurrencesFoundTitle,
                        text: snapshot.occurrencesFoundText
                    )
                    if let occurrencesText = snapshot.occurrencesText {
                        Text(occurrencesText)
                            .font(.caption.weight(.black))
                    }
                } else {
                    LeakNote(kind: .ok, title: snapshot.occurrencesNotFoundTitle)
                }
                LeakPoweredByFooter(text: snapshot.poweredBy)
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
