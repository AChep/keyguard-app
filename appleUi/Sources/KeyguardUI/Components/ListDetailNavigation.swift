#if os(iOS)
import UIKit
#endif

/// A presentation of one canonical path. Window width is deliberately not an input.
struct ListDetailNavigation: Equatable {
    /// iPad shows a list and its details as columns; other devices use one stack.
    @MainActor static var usesPanels: Bool {
        #if os(iOS)
        UIDevice.current.userInterfaceIdiom == .pad
        #else
        false
        #endif
    }

    struct Entry {
        let id: Int64
        var isVaultList = false
    }

    let kind: NavigationListKind?
    let listEntryId: Int64?
    let sidebarPath: [Int64]
    let detailRoot: Int64?
    let detailPath: [Int64]

    init(root: NavigationListKind?, entries: [Entry]) {
        let anchor = entries.lastIndex { $0.isVaultList }
        kind = anchor == nil ? root : .vault
        listEntryId = anchor.map { entries[$0].id }
        let start = anchor.map { $0 + 1 } ?? 0
        sidebarPath = entries.prefix(start).map(\.id)
        let details = kind == nil ? [] : Array(entries.dropFirst(start))
        detailRoot = details.first?.id
        detailPath = details.dropFirst().map(\.id)
    }
}
