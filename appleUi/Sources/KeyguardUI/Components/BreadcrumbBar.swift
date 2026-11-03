import SwiftUI

/// A folder path: every parent node opens its own folder, the last node is
/// the current folder.
struct BreadcrumbBar: View {
    struct Node: Identifiable {
        let id: String
        let title: String
        /// `nil` renders the node as plain text.
        let action: (() -> Void)?
    }

    let nodes: [Node]

    var body: some View {
        FlowLayout(spacing: 4, clampsToWidth: true) {
            ForEach(Array(nodes.enumerated()), id: \.element.id) { index, node in
                if index > 0 {
                    Image(systemName: "chevron.compact.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
                if let action = node.action {
                    if index == nodes.count - 1 {
                        Button(action: action) {
                            Text(node.title).touchTarget()
                        }
                        .buttonStyle(.plain)
                        .breadcrumbAffordance(node.title)
                    } else {
                        Button(action: action) {
                            Text(node.title).touchTarget()
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.secondary)
                        .breadcrumbAffordance(node.title)
                    }
                } else {
                    Text(node.title).touchTarget()
                }
            }
        }
    }
}

private extension View {
    @ViewBuilder
    func breadcrumbAffordance(_ title: String) -> some View {
        #if os(macOS)
        self
            .foregroundStyle(.tint)
            .help(title)
        #else
        self.help(title)
        #endif
    }
}
