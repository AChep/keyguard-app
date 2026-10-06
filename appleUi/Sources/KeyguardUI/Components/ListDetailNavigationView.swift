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
                        detailDestination(id).id(id)
                    } else {
                        ListNoSelectionView(kind: projection.kind ?? .vault)
                    }
                }
                .navigationDestination(for: Int64.self, destination: detailDestination)
            }
        }
        .navigationSplitViewStyle(.automatic)
        .onChange(of: projection, initial: true) { _, value in
            compactColumn = value.detailRoot == nil ? .sidebar : .detail
        }
    }

    /// SwiftUI writes this binding when Back leaves the collapsed detail column.
    /// Resizing changes neither the preferred column nor the canonical path.
    private var preferredCompactColumn: Binding<NavigationSplitViewColumn> {
        Binding(
            get: { compactColumn },
            set: { column in
                let returningToList = compactColumn == .detail && column == .sidebar
                compactColumn = column
                if returningToList && projection.detailRoot != nil { clearDetail() }
            })
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
