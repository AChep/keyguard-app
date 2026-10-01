import SwiftUI

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
