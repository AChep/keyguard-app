import SwiftUI
import KeyguardShared

/// Secondary actions are siblings of a row's primary button, never its parent.
struct DetailActionMenu: View {
    let actions: [VaultActionSnapshot]
    let invoke: (String) -> Void

    var body: some View {
        if !actions.isEmpty {
            Menu {
                listActionMenuItems(actions: actions) { invoke($0) }
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

/// A row's actions as inline bordered buttons; destructive ones render red.
struct BorderedActionButtons: View {
    let actions: [VaultActionSnapshot]
    let invoke: (String) -> Void

    var body: some View {
        ForEach(actions, id: \.id) { action in
            Button(action.title, role: action.danger ? .destructive : nil) { invoke(action.id) }
                .buttonStyle(.bordered)
                .touchTarget()
        }
    }
}
