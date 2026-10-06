import SwiftUI
import KeyguardShared

struct EmailLeakView: View {
    @Environment(DialogsModel.self) private var dialogsModel

    var body: some View {
        ModalSheet(
            title: dialogsModel.emailLeak?.title ?? "",
            width: 460,
            height: 520,
            detents: [.medium, .large]
        ) {
            if let snapshot = dialogsModel.emailLeak {
                content(snapshot)
            } else {
                // Briefly nil mid-dismissal; render nothing.
                Color.clear
            }
        }
    }

    @ViewBuilder
    private func content(_ snapshot: EmailLeakSnapshot) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(snapshot.note)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if snapshot.isLoading {
                    LeakLoadingSkeleton()
                } else if let errorText = snapshot.errorText {
                    LeakNote(kind: .warning, text: errorText)
                } else {
                    LeakBreachList(
                        breaches: snapshot.breaches,
                        breachFoundTitle: snapshot.breachFoundTitle,
                        breachNotFoundTitle: snapshot.breachNotFoundTitle,
                        breachSectionTitle: snapshot.breachSectionTitle
                    )
                }
                LeakPoweredByFooter(text: snapshot.poweredBy)
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
