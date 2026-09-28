import SwiftUI
import KeyguardShared

struct UrlRuleDetailSheet: View {
    let title: String
    let item: UrlRuleItemSnapshot
    let subtitleLabel: String
    let detailLabel: String
    let invokeAction: (String) -> Void

    var body: some View {
        ModalSheet(title: title, detents: [.medium, .large]) {
            Form {
                Section {
                    LabeledContent(L10n.genericName) {
                        Text(item.title)
                            .textSelection(.enabled)
                    }
                    LabeledContent(L10n.status, value: item.active ? L10n.enabled : L10n.disabled)
                }
                Section(subtitleLabel) {
                    Text(item.subtitle)
                        .font(.body.monospaced())
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Section(detailLabel) {
                    Text(item.detail)
                        .font(.body.monospaced())
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
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
