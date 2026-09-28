import SwiftUI

/// A filter backed by the producer's selection, using the platform's button styling.
struct FilterToggle: View {
    let title: String
    let subtitle: String?
    let isOn: Bool
    let action: () -> Void
    @Environment(\.accessibilityDifferentiateWithoutColor) private var differentiateWithoutColor

    private var selection: Binding<Bool> {
        Binding(
            get: { isOn },
            set: { value in
                if value != isOn { action() }
            }
        )
    }

    var body: some View {
        Toggle(isOn: selection) {
            HStack {
                if isOn && differentiateWithoutColor {
                    Image(systemName: "checkmark")
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .toggleStyle(.button)
        .buttonStyle(.bordered)
        .buttonBorderShape(.capsule)
        #if os(iOS)
        .controlSize(.regular)
        .font(.subheadline)
        #endif
    }
}
