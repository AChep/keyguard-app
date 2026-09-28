import SwiftUI

/// The full-bleed loading indicator every snapshot-backed screen shows while its
/// shared producer has not emitted yet.
struct LoadingIndicator: View {
    var body: some View {
        ProgressView()
            .controlSize(.large)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// The loading / empty / content tri-state every snapshot-backed screen renders.
///
/// A shared producer emits a snapshot carrying a `loaded` flag and an item list, so
/// nearly every screen in the package opens with the same three-way branch. Keeping
/// it here means the loading affordance is tuned in one place rather than in ~40
/// hand-written copies.
///
/// ```swift
/// SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
///     ContentUnavailableView { Label(L10n.downloadsEmptyLabel, systemImage: "arrow.down.circle") }
/// } content: {
///     list
/// }
/// .navigationTitle(L10n.downloads)
/// ```
struct SnapshotContent<Empty: View, Content: View>: View {
    let loaded: Bool
    let isEmpty: Bool
    @ViewBuilder let empty: Empty
    @ViewBuilder let content: Content

    var body: some View {
        if !loaded {
            LoadingIndicator()
        } else if isEmpty {
            empty
        } else {
            content
        }
    }
}

extension SnapshotContent where Empty == EmptyView {
    /// A screen with no distinct empty state — its content handles an empty list.
    init(loaded: Bool, @ViewBuilder content: () -> Content) {
        self.init(loaded: loaded, isEmpty: false, empty: { EmptyView() }, content: content)
    }
}
