#if os(macOS)
import SwiftUI
import KeyguardShared

struct SendActionsToolbarButton: View {
    let actions: [VaultActionSnapshot]
    let invoke: @MainActor @Sendable (String) -> Void

    var body: some View {
        Menu {
            listActionMenuItems(actions: actions, invoke: invoke)
        } label: {
            Label(L10n.more, systemImage: "ellipsis.circle")
        }
        .help(L10n.more)
    }
}
#endif
