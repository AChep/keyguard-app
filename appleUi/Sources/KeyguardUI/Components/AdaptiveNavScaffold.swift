import SwiftUI

#if os(iOS)
struct AdaptiveNavScaffold<CompactRoot: View, Sidebar: View, Detail: View>: View {
    /// The Kotlin nav-stack scope this section renders ("vault", "settings", …).
    let scope: String
    @ViewBuilder var compact: () -> CompactRoot
    @ViewBuilder var sidebar: () -> Sidebar
    @ViewBuilder var detail: () -> Detail

    @Environment(NavigationModel.self) private var navigationModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        Group {
            if horizontalSizeClass == .compact {
                NavStackContainer(scope: scope, observesScope: false) {
                    compact()
                }
            } else {
                NavigationSplitView {
                    sidebar()
                } detail: {
                    NavStackContainer(scope: scope, observesScope: false) {
                        detail()
                    }
                }
                .navigationSplitViewStyle(.balanced)
            }
        }
        // An iPad detail stack disappears during a push. Its scaffold must retain
        // observation so that transition cannot clear the navigation path.
        .observing(
            start: { navigationModel.startNavScopeObservation(scope) },
            stop: { navigationModel.stopNavScopeObservation(scope) }
        )
    }
}
#endif
