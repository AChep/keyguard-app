import SwiftUI

/// One option of a single choice in a wizard step.
struct WizardChoiceRow: View {
    let title: String
    var detail: String? = nil
    let systemImage: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.title2)
                    .foregroundStyle(.tint)
                    .frame(width: 36)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.headline).foregroundStyle(.primary)
                    if let detail {
                        Text(detail).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
                    .accessibilityHidden(true)
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// The primary action of a wizard step.
struct WizardPrimaryButton<Label: View>: View {
    let action: () -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        Button(action: action) {
            label()
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .keyboardShortcut(.defaultAction)
    }
}

extension View {
    /// Pins the controls of a wizard step below its scrolling content.
    func wizardFooter<Footer: View>(@ViewBuilder _ footer: () -> Footer) -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) {
            footer()
                .padding(16)
                .background(.bar)
        }
    }
}
