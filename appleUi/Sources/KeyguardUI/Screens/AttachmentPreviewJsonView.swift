import SwiftUI
import KeyguardShared

/// Expansion belongs to the parsed tree, so switching tabs preserves it.
struct AttachmentPreviewJsonView<Source: View>: View {
    let tree: AttachmentPreviewJsonTreeSnapshot?
    let errorMessage: String?
    let showTree: Bool
    @ViewBuilder let source: () -> Source
    @State private var expandedIds: Set<String>
    @State private var hasShownSource = false

    init(
        tree: AttachmentPreviewJsonTreeSnapshot?,
        errorMessage: String?,
        showTree: Bool,
        @ViewBuilder source: @escaping () -> Source
    ) {
        self.tree = tree
        self.errorMessage = errorMessage
        self.showTree = showTree
        self.source = source
        _expandedIds = State(initialValue: tree.map { [$0.rootId] } ?? [])
    }

    var body: some View {
        if let tree {
            // Keep visited panes mounted to retain their independent native scroll offsets,
            // including horizontal source scrolling, on macOS 14 as well as iOS.
            ZStack {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(tree.visibleRows(expandedIds: expandedIds), id: \.id) { row in
                            VStack(spacing: 0) {
                                if row.id != tree.rootId { Divider() }
                                AttachmentPreviewJsonRow(
                                    row: row,
                                    expanded: expandedIds.contains(row.id),
                                    toggle: { toggle(row.id) }
                                )
                                .padding(.leading, CGFloat(min(row.depth, 3)) * 16)
                                .padding(.vertical, 4)
                            }
                        }
                    }
                }
                .opacity(showTree ? 1 : 0)
                .allowsHitTesting(showTree)
                .accessibilityHidden(!showTree)

                if hasShownSource || !showTree {
                    source()
                        .opacity(showTree ? 0 : 1)
                        .allowsHitTesting(!showTree)
                        .accessibilityHidden(showTree)
                }
            }
            .onChange(of: showTree, initial: true) { _, showingTree in
                if !showingTree { hasShownSource = true }
            }
        } else {
            VStack(alignment: .leading, spacing: 16) {
                if let errorMessage {
                    Label(errorMessage, systemImage: "info.circle")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                source()
            }
        }
    }

    private func toggle(_ id: String) {
        if expandedIds.contains(id) {
            expandedIds.remove(id)
        } else {
            expandedIds.insert(id)
        }
    }
}
