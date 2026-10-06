import SwiftUI
import KeyguardShared

struct ServiceInfoDialogView: View {
    @Environment(DialogsModel.self) private var dialogsModel

    var body: some View {
        ModalSheet(
            title: dialogsModel.serviceInfo?.title ?? "",
            width: 460,
            height: 480,
            detents: [.medium, .large]
        ) {
            if let snapshot = dialogsModel.serviceInfo {
                ScrollView {
                    ServiceDirectoryDetailContent(detail: snapshot)
                        .padding(24)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            } else {
                // Briefly nil mid-dismissal; render nothing.
                Color.clear
            }
        }
    }
}
