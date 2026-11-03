import SwiftUI
import KeyguardShared

/// The item and the field a value is read from, or the field and an error
/// if the item is not available; tapping it opens the item.
struct DetailReferenceChip: View {
    let reference: VaultReferenceSnapshot
    let invoke: (String) -> Void

    var body: some View {
        // A disabled button dims its label, the error must stay readable.
        if let clickActionId = reference.clickActionId {
            Button {
                invoke(clickActionId)
            } label: {
                label
            }
            .buttonStyle(.plain)
        } else {
            label
        }
    }

    private var text: String? {
        reference.unavailable ? L10n.environmentsReferenceUnavailable : reference.text
    }

    private var accent: AnyShapeStyle {
        reference.unavailable ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary)
    }

    private var label: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: reference.unavailable ? "exclamationmark.triangle" : "link")
                .foregroundStyle(accent)
                .accessibilityHidden(true)
            Text(reference.title)
                .lineLimit(1)
            if let text {
                Text(text)
                    .lineLimit(1)
                    .layoutPriority(1)
                    .foregroundStyle(accent)
            }
            if reference.clickActionId != nil {
                Image(systemName: "chevron.forward")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .font(.subheadline)
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 8).fill(.quaternary))
        .accessibilityElement(children: .combine)
        .touchTarget()
    }
}
