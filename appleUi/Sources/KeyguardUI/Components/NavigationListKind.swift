import SwiftUI

enum NavigationListKind: Equatable {
    case vault
    case send
}

/// The detail column's prompt while the list has nothing selected.
struct ListNoSelectionView: View {
    let kind: NavigationListKind

    var body: some View {
        ContentUnavailableView {
            Label(
                kind == .send ? L10n.sendViewNoSelectionTitle : L10n.vaultViewNoItemSelectedTitle,
                systemImage: "sidebar.right")
        } description: {
            Text(kind == .send ? L10n.sendViewNoSelectionText : L10n.vaultViewSelectItemHint)
        }
    }
}
