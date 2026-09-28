import SwiftUI

struct FieldAccessibility: ViewModifier {
    let label: String?
    let value: String?

    @ViewBuilder
    func body(content: Content) -> some View {
        if let value, !value.isEmpty {
            // Explicit value present: safe to name the element with the title and
            // carry the content in the value (replacing the masked auto-label).
            if let label, !label.isEmpty {
                content
                    .accessibilityLabel(label)
                    .accessibilityValue(value)
            } else {
                content.accessibilityValue(value)
            }
        } else {
            // No explicit value: keep the combined auto-label (the visible value
            // text). Overriding it with just the title here would silence the value.
            content
        }
    }
}
