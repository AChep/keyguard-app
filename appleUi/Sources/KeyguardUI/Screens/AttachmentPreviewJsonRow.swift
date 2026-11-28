import SwiftUI
import KeyguardShared

/// A context menu, or an accessibility action, copies the row's full value.
struct AttachmentPreviewJsonRow: View {
    let row: AttachmentPreviewJsonNodeSnapshot
    let expanded: Bool
    let toggle: () -> Void
    let copy: () -> Void

    var body: some View {
        let title = copyTitle
        content
            .contextMenu {
                Button(title, systemImage: "doc.on.doc", action: copy)
            }
            .accessibilityAction(named: title, copy)
    }

    @ViewBuilder
    private var content: some View {
        if row.expandable {
            Button(action: toggle) {
                label
                    .touchTarget()
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(accessibilityLabel)
            .accessibilityValue(expanded ? L10n.attachmentPreviewJsonExpanded : L10n.attachmentPreviewJsonCollapsed)
        } else {
            label
                .touchTarget()
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(accessibilityLabel)
        }
    }

    private var label: some View {
        VStack(alignment: .leading, spacing: 4) {
            DetailAccessoryRow {
                DetailRowLabel(
                    title: row.key,
                    subtitle: value,
                    disclosure: row.expandable ? (expanded ? "chevron.down" : "chevron.forward") : nil
                )
                .lineLimit(2)
            } accessories: {
                Text(typeName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if row.depth > 3 {
                Text(row.parentPath)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
    }

    /// Objects and arrays copy as JSON, other values as plain text.
    private var copyTitle: String {
        row.type == .object || row.type == .array ? L10n.copyJson : L10n.copyValue
    }

    private var typeName: String {
        switch row.type {
        case .object: L10n.attachmentPreviewJsonObject
        case .array: L10n.attachmentPreviewJsonArray
        case .string: L10n.attachmentPreviewJsonString
        case .number: L10n.attachmentPreviewJsonNumber
        case .boolean: L10n.attachmentPreviewJsonBoolean
        default: L10n.attachmentPreviewJsonNull
        }
    }

    private var value: String {
        if row.type == .object {
            return L10n.attachmentPreviewJsonProperties(Int(row.childCount))
        }
        if row.type == .array {
            return L10n.attachmentPreviewJsonItems(Int(row.childCount))
        }
        return row.value ?? ""
    }

    private var accessibilityLabel: String {
        let context = row.depth > 3 ? [row.parentPath] : []
        return ([row.key, typeName, value] + context).joined(separator: ", ")
    }
}
