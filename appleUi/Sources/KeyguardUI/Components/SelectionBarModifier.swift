import SwiftUI
import KeyguardShared

/// Reserves room for the actions so the last selected row can scroll above them.
struct SelectionBarModifier: ViewModifier {
    let count: Int32
    let visible: Bool
    let actions: [VaultActionSnapshot]
    let invoke: @MainActor @Sendable (String) -> Void
    let clear: @MainActor @Sendable () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            ZStack {
                if visible {
                    SelectionActionBar(count: Int(count), actions: actions, invoke: invoke, clear: clear)
                        .padding(.bottom, 12)
                        .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(reduceMotion ? nil : .spring(duration: 0.3), value: visible)
        }
    }
}
