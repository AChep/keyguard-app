import SwiftUI

#if os(iOS)
struct AdaptiveNavScaffold<CompactRoot: View, Sidebar: View, Detail: View>: View {
    /// The Kotlin nav-stack scope this section renders ("vault", "settings", …).
    let scope: String
    /// The iPhone-compact root (e.g. the list whose rows push onto the stack).
    @ViewBuilder var compact: () -> CompactRoot
    /// The iPad-regular sidebar column (selection-driven).
    @ViewBuilder var sidebar: () -> Sidebar
    /// The iPad-regular detail pane (the stack layers producer pushes above it).
    @ViewBuilder var detail: () -> Detail

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        if horizontalSizeClass == .compact {
            NavStackContainer(scope: scope) {
                compact()
            }
        } else {
            NavigationSplitView {
                sidebar()
            } detail: {
                NavStackContainer(scope: scope) {
                    detail()
                }
            }
            .navigationSplitViewStyle(.balanced)
        }
    }
}
#endif
