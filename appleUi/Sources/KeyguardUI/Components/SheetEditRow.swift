import SwiftUI

/// A settings row that opens a sheet to edit its value: the title over a [detail]
/// line, and a pencil glyph.
struct SheetEditRow<Detail: View>: View {
    let title: String
    let action: () -> Void
    @ViewBuilder let detail: () -> Detail

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .foregroundStyle(.primary)
                    detail()
                }
                Spacer()
                // The row opens a sheet (not a push), so a navigation chevron would
                // mislead (and chevron.right points the wrong way in RTL). A pencil
                // glyph signals an editable value and is direction-neutral.
                Image(systemName: "pencil")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}
