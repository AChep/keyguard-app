import SwiftUI

struct MasterDetailLayout<Filters: View, ListColumn: View, Detail: View>: View {
    /// Bound to the owner's state so its toolbar can hide the filter *menu* while
    /// the filter *sidebar* is on screen. Driven here from the available width.
    @Binding var filterSidebarShown: Bool
    @ViewBuilder var filters: () -> Filters
    @ViewBuilder var list: () -> ListColumn
    @ViewBuilder var detail: () -> Detail

    var body: some View {
        GeometryReader { proxy in
            HStack(spacing: 0) {
                if filterSidebarShown {
                    filters()
                        .frame(width: SidebarLayout.width)
                    Divider()
                }
                list()
                    .frame(width: SidebarLayout.listWidth)
                Divider()
                detail()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
            .onAppear { recompute(proxy.size.width) }
            .onChange(of: proxy.size.width) { _, width in recompute(width) }
        }
    }

    private func recompute(_ width: CGFloat) {
        let shouldShow = width >= SidebarLayout.filterCollapseThreshold
        if filterSidebarShown != shouldShow {
            filterSidebarShown = shouldShow
            // Remember the decision so the next mount's FIRST render already
            // matches (see `FilterSidebarMemory`) — otherwise every visit
            // re-renders the toolbar to remove the filters item a frame later.
            FilterSidebarMemory.mainWide = shouldShow
        }
    }
}
