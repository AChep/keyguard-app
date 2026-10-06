import Foundation
import KeyguardShared

/// Rendering seam for vault-list rows and their actions.
@MainActor
protocol VaultRowListModel: AnyObject {
    /// The per-cell content cache the renderers diff over.
    var store: VaultRowStore { get }

    /// Live TOTP codes keyed by row id, pushed at 1Hz.
    var totpStates: [String: TotpFieldSnapshot] { get }
    var selection: VaultSelection { get }
    var filterState: VaultFilterState { get }
    /// Whether a search query is active; hides the quick-filter chips.
    var isQueryActive: Bool { get }
    /// The saved-filter chips rendered at the quick-filters marker row.
    var quickFilterChips: [VaultFilterChip] { get }
    /// iPad: the row whose detail is open beside the list.
    var browseSelectedRowId: String? { get }

    func openVaultRow(rowId: String)
    /// Backs `VaultListConfig.RowTap.copyPrimary` (Recents copies the password on tap).
    func copyPrimaryRow(rowId: String)
    func selectRow(rowId: String)
    func performVaultRowAction(rowId: String, actionId: String)
    func performVaultBadgeTap(rowId: String, badgeId: String)
    /// Resolves a row's context-menu actions on demand (never carried in state).
    func rowActions(rowId: String) async -> [VaultAction]
    func toggleSelection(rowId: String)
    func invokeSelectionAction(id: String)
    func invokeSelectionAction(id: String, selectedIds: Set<String>)
    func invokeFilter(id: String)
    func reportScroll(anchorId: String, offset: Int)
    /// Marks the row the detail pane currently shows (recents / shape accents).
    func setOpenedRow(rowId: String)
}

/// Defaults for everything a lightweight surface won't implement, so a conformer
/// need only supply `store` (plus whatever it genuinely renders / commands).
extension VaultRowListModel {
    var totpStates: [String: TotpFieldSnapshot] { [:] }
    var selection: VaultSelection { .empty }
    var filterState: VaultFilterState { .empty }
    var isQueryActive: Bool { false }
    var quickFilterChips: [VaultFilterChip] { [] }
    var browseSelectedRowId: String? { nil }

    func openVaultRow(rowId: String) {}
    func copyPrimaryRow(rowId: String) {}
    func selectRow(rowId: String) {}
    func performVaultRowAction(rowId: String, actionId: String) {}
    func performVaultBadgeTap(rowId: String, badgeId: String) {}
    func rowActions(rowId: String) async -> [VaultAction] { [] }
    func toggleSelection(rowId: String) {}
    func invokeSelectionAction(id: String) {}
    func invokeSelectionAction(id: String, selectedIds: Set<String>) {}
    func invokeFilter(id: String) {}
    func reportScroll(anchorId: String, offset: Int) {}
    func setOpenedRow(rowId: String) {}
}

/// Interaction policy shared by the vault-list surfaces.
struct VaultListConfig {
    /// What a primary (browse) tap on a cipher row does.
    enum RowTap: Equatable {
        /// Push the row's detail (iOS main list).
        case open
        /// Copy the row's primary value (Recents).
        case copyPrimary
        /// Select the row — drives the inline detail column (macOS main list).
        case select
        case openOrToggle
    }

    var rowTap: RowTap
    var supportsMultiSelect: Bool
    /// Item rows carry a context menu.
    var contextMenu: Bool
    /// iOS: pin a sync-status header row atop the list when the vault has
    /// sync errors / pending uploads. No-op on macOS.
    var syncHeader: Bool
    /// Render the saved-filter quick-filter chips at the quick-filters row.
    var quickFilters: Bool
    /// Replace a wholly empty list's marker with a centered native empty state.
    var usesNativeEmptyState = false
    /// A single highlighted row id, for surfaces that track ONE selection out of
    /// band. The main list uses the multi-select `VaultSelection` channel instead,
    /// so it leaves this `nil`.
    var selectedRowId: String? = nil
    var highlightsSelectedRowId: Bool = false

    static var vaultMain: VaultListConfig {
        #if os(macOS)
        VaultListConfig(
            rowTap: .select,
            supportsMultiSelect: true,
            contextMenu: true,
            syncHeader: false,
            quickFilters: true,
            usesNativeEmptyState: true
        )
        #else
        VaultListConfig(
            rowTap: .open,
            supportsMultiSelect: true,
            contextMenu: true,
            syncHeader: true,
            quickFilters: true,
            usesNativeEmptyState: true
        )
        #endif
    }

    /// A stacked filtered list or a custom-filter tab.
    static var stacked: VaultListConfig {
        VaultListConfig(
            rowTap: .open,
            supportsMultiSelect: false,
            contextMenu: true,
            syncHeader: false,
            quickFilters: false,
            usesNativeEmptyState: true
        )
    }
}
