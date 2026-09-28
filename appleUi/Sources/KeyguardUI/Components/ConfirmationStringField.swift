import SwiftUI
import KeyguardShared

/// One stable form row, including validation, so snapshot updates do not replace
/// the focused native field when an error appears or disappears.
struct ConfirmationStringField: View {
    let item: ConfirmationItemSnapshot
    let onChange: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            LabeledContent {
                BridgedTextField(
                    label: item.title.isEmpty ? (item.hint ?? "") : item.title,
                    prompt: item.hint,
                    text: item.stringValue,
                    textRevision: Int32(item.stringRevision),
                    secure: item.password,
                    send: onChange,
                    style: .automatic
                )
                .labelsHidden()
                .accessibilityLabel(item.title.isEmpty ? (item.hint ?? "") : item.title)
                .font(item.monospace ? .system(.body, design: .monospaced) : nil)
                .disabled(!item.enabled)
            } label: {
                if !item.title.isEmpty {
                    Text(item.title)
                }
            }
            if let description = item.descriptionText, !description.isEmpty {
                Text(description)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let error = item.error, !error.isEmpty {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
    }
}
