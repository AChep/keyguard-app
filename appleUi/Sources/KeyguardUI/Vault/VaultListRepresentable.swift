import SwiftUI

/// The virtualizing vault list, resolved to the platform's native list bridge:
/// `NSTableView` on macOS, `UICollectionView` on iOS.
///
/// Every vault-list surface (the split-view pane, the stacked list, a custom
/// filter tab, Duplicates) renders the same two representables behind the same
/// `#if os(...)`, so the split lives here once. Representables aren't auto-extended
/// under system bars like SwiftUI lists are. The viewport extends under chrome
/// here; native scrolling insets keep the first and last rows reachable.
struct VaultListRepresentable: View {
    let model: any VaultRowListModel
    let selection: VaultSelectionModel
    /// The app-lifetime model, read by the iOS bridge for its sync-status header.
    let accountsModel: AccountsModel
    let config: VaultListConfig
    let colorScheme: ColorScheme

    @Environment(\.vaultListBottomBarHeight) private var bottomBarHeight

    /// iOS `EditMode` gate: drives multi-select and the row context-menu swap.
    /// Ignored on macOS, which has no Edit button.
    var editing: Bool = false

    /// Whether to extend the rows under the window toolbar / navigation bar.
    /// `false` for surfaces hosted in a panel or popover, which have no such chrome
    /// (Recents, Quick Search).
    var extendsUnderChrome: Bool = true

    /// macOS only. The `NSTableView` bridge reports a row tap as a selection change;
    /// when `true` that is mirrored into an `openVaultRow` push and the momentary
    /// highlight is cleared (tap-to-open). Leave `false` on surfaces whose row-tap
    /// policy the bridge already resolves itself (`.openOrToggle`).
    var opensOnSelection: Bool = false

    var body: some View {
        Group {
            #if os(macOS)
            VaultListTableView(model: model, selection: selection, config: config, colorScheme: colorScheme)
                .ignoresSafeArea(edges: extendsUnderChrome ? .top : [])
                .onChange(of: selection.selectedRowIds) { _, newValue in
                    guard opensOnSelection, let id = newValue.first else { return }
                    model.openVaultRow(rowId: id)
                    selection.selectedRowIds = []
                }
            #else
            VaultListCollectionView(
                model: model,
                selection: selection,
                accountsModel: accountsModel,
                config: config,
                colorScheme: colorScheme,
                editing: editing,
                additionalBottomInset: extendsUnderChrome ? bottomBarHeight : 0
            )
            // Extend only under container chrome; keep SwiftUI keyboard avoidance.
            .ignoresSafeArea(.container, edges: extendsUnderChrome ? .vertical : [])
            #endif
        }
        .overlay {
            if config.usesNativeEmptyState && VaultListProjection.hasNoItems(in: model.store.structure.entries) {
                ListEmptyState(
                    title: L10n.itemsEmptyLabel, systemImage: "tray", isSearching: model.isQueryActive
                )
                // Search, sync status, and filter chips remain interactive below
                // the empty-state presentation; the native list stays mounted.
                .allowsHitTesting(false)
            }
        }
    }
}
