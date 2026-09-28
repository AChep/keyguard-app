import SwiftUI

struct FilterSidebarActionsModifier: ViewModifier {
    let canClear: Bool
    let canSave: Bool
    let clear: () -> Void
    let save: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            ZStack {
                if canClear {
                    HStack(spacing: 8) {
                        FilterSidebarActionButton(
                            title: L10n.filterClearAction,
                            systemImage: "xmark.circle",
                            iconOnly: false,
                            destructive: true,
                            action: clear
                        )
                        if canSave {
                            FilterSidebarActionButton(
                                title: L10n.customfiltersAddFilterTitle,
                                systemImage: "plus.circle",
                                iconOnly: true,
                                destructive: false,
                                action: save
                            )
                        }
                    }
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(reduceMotion ? nil : .spring(duration: 0.3), value: canClear)
            .animation(reduceMotion ? nil : .spring(duration: 0.3), value: canSave)
        }
    }
}
