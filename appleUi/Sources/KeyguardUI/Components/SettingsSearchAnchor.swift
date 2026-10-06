import SwiftUI
import KeyguardShared

private struct SettingsSearchAnchor: ViewModifier {
    @Environment(\.settingsRevealRequest) private var request
    @AccessibilityFocusState private var accessibilityFocused: Bool
    let target: SettingsSearchTarget

    private var highlighted: Bool { request?.target == target }

    func body(content: Content) -> some View {
        content
            .overlay {
                if highlighted {
                    RoundedRectangle(cornerRadius: 6)
                        .strokeBorder(.primary, lineWidth: 2)
                        .padding(-4)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
            .accessibilityFocused($accessibilityFocused)
            .onChange(of: request, initial: true) {
                if highlighted { accessibilityFocused = true }
            }
    }
}

extension View {
    /// Apply last on a Form row so lazy iOS lists can discover its scroll identity.
    func settingsSearchTarget(_ target: SettingsSearchTarget) -> some View {
        modifier(SettingsSearchAnchor(target: target))
            // Keep the identity on the row, outside the modifier. iOS Forms use
            // lazy lists and must discover the target before the row is created.
            .id(target.name)
    }
}
