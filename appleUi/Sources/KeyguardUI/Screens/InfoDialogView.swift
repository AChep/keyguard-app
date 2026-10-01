import SwiftUI
import KeyguardShared

struct InfoDialogView: View {
    @Environment(DialogsModel.self) private var dialogsModel

    var body: some View {
        ModalSheet(
            title: dialogsModel.infoDialog?.title ?? "",
            width: 460,
            height: 300,
            detents: [.medium]
        ) {
            if let snapshot = dialogsModel.infoDialog {
                content(snapshot)
            } else {
                // Briefly nil mid-dismissal; render nothing.
                Color.clear
            }
        }
    }

    private func isEmpty(_ snapshot: InfoDialogSnapshot) -> Bool {
        let hasSubtitle = !(snapshot.subtitle?.isEmpty ?? true)
        return !hasSubtitle && snapshot.flags.isEmpty
    }

    @ViewBuilder
    private func content(_ snapshot: InfoDialogSnapshot) -> some View {
        if isEmpty(snapshot) {
            // Both subtitle and flags are empty (a legitimate case per the shared
            // producer); show a muted placeholder rather than a blank panel.
            ContentUnavailableView {
                Label(L10n.infoDialogEmptyText, systemImage: "info.circle")
            }
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if let subtitle = snapshot.subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(snapshot.flags, id: \.self) { flag in
                        Text(flag)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
            }
        }
    }
}
