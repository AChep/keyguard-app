import SwiftUI

extension View {
    /// Wraps the view in a Liquid Glass capsule on macOS / iOS 26+, falling back
    /// to a frosted material capsule on earlier versions.
    @ViewBuilder
    func glassCapsule() -> some View {
        if #available(macOS 26.0, iOS 26.0, *) {
            self.glassEffect(.regular.interactive(), in: .capsule)
        } else {
            self
                .background(.regularMaterial, in: Capsule())
                .overlay(Capsule().strokeBorder(Color(platform: .platformSeparator), lineWidth: 1))
                .shadow(color: .black.opacity(0.12), radius: 6, y: 2)
        }
    }

    /// The floating "Clear filters" / "Save filters" actions pinned to the bottom of
    /// a filter sidebar, reserving enough space to keep its last row reachable.
    func filterSidebarActions(
        canClear: Bool,
        canSave: Bool,
        clear: @escaping () -> Void,
        save: @escaping () -> Void
    ) -> some View {
        modifier(FilterSidebarActionsModifier(canClear: canClear, canSave: canSave, clear: clear, save: save))
    }
}

struct FilterSidebarActionButton: View {
    let title: String
    let systemImage: String
    let iconOnly: Bool
    let destructive: Bool
    let action: () -> Void

    var body: some View {
        Button(role: destructive ? .destructive : nil, action: action) {
            label
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .touchTarget()
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .help(title)
        .glassCapsule()
        .padding(.bottom, 12)
    }

    // The two label styles resolve to different concrete types, so they cannot be
    // selected by a ternary inside one `.labelStyle(...)`.
    @ViewBuilder
    private var label: some View {
        if iconOnly {
            Label(title, systemImage: systemImage).labelStyle(.iconOnly)
        } else {
            Label(title, systemImage: systemImage).labelStyle(.titleAndIcon)
        }
    }
}
