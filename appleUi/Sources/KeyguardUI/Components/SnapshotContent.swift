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
