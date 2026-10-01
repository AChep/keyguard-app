import SwiftUI

#if os(iOS)
struct AdaptiveNavScaffold<CompactRoot: View, Sidebar: View, Detail: View>: View {
    /// The Kotlin nav-stack scope this section renders ("vault", "settings", …).
    let scope: String
    @ViewBuilder var compact: () -> CompactRoot
    @ViewBuilder var sidebar: () -> Sidebar
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
