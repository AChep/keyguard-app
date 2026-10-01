import SwiftUI
#if os(iOS)
import UIKit
#endif

/// A filter backed by the producer's selection.
struct FilterToggle: View {
    let title: String
    let subtitle: String?
    let isOn: Bool
    let action: () -> Void
    @Environment(\.accessibilityDifferentiateWithoutColor) private var differentiateWithoutColor

    private var usesPhoneStyle: Bool {
        #if os(iOS)
        UIDevice.current.userInterfaceIdiom == .phone
        #else
        false
        #endif
    }

    private var selection: Binding<Bool> {
        Binding(
            get: { isOn },
            set: { value in
                if value != isOn { action() }
            }
        )
    }

    var body: some View {
        let toggle = Toggle(isOn: selection) {
            HStack {
                if usesPhoneStyle && isOn && differentiateWithoutColor {
                    Image(systemName: "checkmark")
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption2)
                            .foregroundStyle(
                                !usesPhoneStyle && isOn
                                    ? Color.accentColor.contrastingTextColor.opacity(0.85) : .secondary)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .toggleStyle(.button)
        .accessibilityAddTraits(isOn ? .isSelected : [])

        #if os(iOS)
        Group {
            if usesPhoneStyle {
                toggle
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                    .controlSize(.regular)
            } else {
                toggle
                    .buttonStyle(FilterToggleButtonStyle(isOn: isOn))
            }
        }
        .font(.subheadline)
        #else
        toggle
            .buttonStyle(.bordered)
            .buttonBorderShape(.capsule)
        #endif
    }
}
