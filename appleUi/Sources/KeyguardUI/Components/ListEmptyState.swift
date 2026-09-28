import SwiftUI

/// Native empty-list styling shared by Send and the vault list surfaces.
struct ListEmptyState: View {
    let title: String
    let systemImage: String
    let isSearching: Bool

    var body: some View {
        if isSearching {
            ContentUnavailableView.search
        } else {
            ContentUnavailableView {
                Label(title, systemImage: systemImage)
            }
        }
    }
}
