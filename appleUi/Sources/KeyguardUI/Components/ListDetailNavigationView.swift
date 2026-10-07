import SwiftUI

#if os(iOS)
/// Native column adaptation, separate from session ownership and route producers.
struct ListDetailNavigationView<Sidebar: View, SidebarDestination: View, DetailDestination: View>: View {
    let projection: ListDetailNavigation
    let popTo: (Int64?) -> Void
    let clearDetail: () -> Void
    @ViewBuilder var sidebar: () -> Sidebar
    @ViewBuilder var sidebarDestination: (Int64) -> SidebarDestination
    @ViewBuilder var detailDestination: (Int64) -> DetailDestination

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var columnVisibility: NavigationSplitViewVisibility = .automatic
    @State private var compactColumn: NavigationSplitViewColumn = .sidebar

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility, preferredCompactColumn: preferredCompactColumn) {
            NavigationStack(path: sidebarPath) {
                sidebar()
                    .navigationDestination(for: Int64.self, destination: sidebarDestination)
            }
        } detail: {
            NavigationStack(path: detailPath) {
                Group {
                    if let id = projection.detailRoot {
                        detailDestination(id)
                            .id(id)
                            .navigationBarBackButtonHidden(horizontalSizeClass == .compact)
                            .toolbar {
                                if horizontalSizeClass == .compact {
                                    ToolbarItem(placement: .topBarLeading) {
                                        Button(action: clearDetail) {
                                            Label(
                                                projection.kind == .send ? L10n.homeSendLabel : L10n.homeVaultLabel,
                                                systemImage: "chevron.backward")
                                        }
                                        .accessibilityIdentifier("listDetailBackToList")
                                    }
                                }
                            }
                    } else {
                        ListNoSelectionView(kind: projection.kind ?? .vault)
                    }
                }
                .navigationDestination(for: Int64.self, destination: detailDestination)
            }
        }
        .navigationSplitViewStyle(.automatic)
        .onChange(of: projection.detailRoot, initial: true) { _, detailRoot in
            compactColumn = detailRoot == nil ? .sidebar : .detail
        }
    }

    /// Column changes are presentation state, not requests to discard a route.
    /// Nested Back is handled by detailPath; the root Back button clears selection.
    private var preferredCompactColumn: Binding<NavigationSplitViewColumn> {
        Binding(
            get: { compactColumn },
            set: { compactColumn = $0 })
    }

    private var sidebarPath: Binding<[Int64]> {
        Binding(
            get: { projection.sidebarPath },
            set: { path in
                guard path.count < projection.sidebarPath.count else { return }
                popTo(path.last)
            })
    }

    private var detailPath: Binding<[Int64]> {
        Binding(
            get: { projection.detailPath },
            set: { path in
                guard path.count < projection.detailPath.count else { return }
                popTo(path.last ?? projection.detailRoot)
            })
    }
}
#endif
