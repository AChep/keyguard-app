import SwiftUI
import KeyguardShared

/// Secondary actions are siblings of a row's primary button, never its parent.
struct DetailActionMenu: View {
    let actions: [VaultActionSnapshot]
    let invoke: (String) -> Void

    var body: some View {
        if !actions.isEmpty {
            Menu {
                ForEach(actions, id: \.id) { action in
                    Button(action.title) { invoke(action.id) }
                }
            } label: {
                Label(L10n.moreActions, systemImage: "ellipsis")
                    .labelStyle(.iconOnly)
                    .touchTarget()
            }
            .menuStyle(.button)
            .buttonStyle(.borderless)
            .menuIndicator(.hidden)
            .help(L10n.moreActions)
        }
    }
}
