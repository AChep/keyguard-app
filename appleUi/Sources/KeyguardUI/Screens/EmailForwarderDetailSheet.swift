import SwiftUI
import KeyguardShared

struct EmailForwarderDetailSheet: View {
    let item: EmailRelayListItemSnapshot
    let invokeAction: (String) -> Void

    var body: some View {
        ModalSheet(title: L10n.emailrelayIntegrationTitle, height: 380, detents: [.medium, .large]) {
            Form {
                Section {
                    LabeledContent(L10n.genericName) {
                        Text(item.title)
                            .textSelection(.enabled)
                    }
                    if !item.service.isEmpty {
                        LabeledContent(L10n.type, value: item.service)
                    }
                }
                Section(L10n.actions) {
                    ForEach(item.actions, id: \.id) { action in
                        ListItemActionButton(action: action, invoke: invokeAction)
                    }
                }
            }
            .formStyle(.grouped)
        }
    }
}
