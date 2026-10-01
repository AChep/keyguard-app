import SwiftUI

#if os(iOS)
/// Keeps filter state visible inside UIKit-hosted cells and translucent sidebars.
struct FilterToggleButtonStyle: ButtonStyle {
    let isOn: Bool
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.colorSchemeContrast) private var contrast

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(isOn ? Color.accentColor.contrastingTextColor : .primary)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .frame(minWidth: 44, minHeight: 32)
            .background(isOn ? Color.accentColor : .clear, in: .capsule)
            .overlay {
                Capsule().strokeBorder(
                    isOn ? Color.clear : Color.secondary.opacity(contrast == .increased ? 0.7 : 0.35),
                    lineWidth: 1)
            }
            // Keep the capsule compact without shrinking its touch target.
            .touchTarget()
            .opacity(isEnabled ? (configuration.isPressed ? 0.7 : 1) : 0.4)
    }
}
#endif
