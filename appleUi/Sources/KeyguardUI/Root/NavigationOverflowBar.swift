import SwiftUI

#if os(iOS)
/// The native TabView still hosts the screens; this row supplies the controls
/// only when a separate overflow menu is needed.
struct NavigationOverflowBar: View {
    let tabs: [NavigationTab]
    @Binding var selection: String
    let showLabels: Bool

    private var overflow: [NavSection] { tabs.filter(\.isOverflow).map(\.section) }
    private var selectedOverflow: NavSection? { overflow.first { $0.key == selection } }

    var body: some View {
        HStack(spacing: 8) {
            HStack(spacing: 0) {
                ForEach(tabs.filter { !$0.isOverflow }) { tab in
                    Button {
                        selection = tab.section.key
                    } label: {
                        NavigationTabLabel(
                            title: tab.section.title,
                            systemImage: tab.section.systemImage,
                            showTitle: showLabels
                        )
                        .frame(maxWidth: .infinity)
                        // `Color.accentColor` ignores the app's tint color setting.
                        .foregroundStyle(selection == tab.id ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                        .background {
                            if selection == tab.id {
                                Capsule().fill(.primary.opacity(0.08))
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(tab.section.title)
                    .accessibilityAddTraits(selection == tab.id ? .isSelected : [])
                }
            }
            .padding(4)
            .glassCapsule()

            NavigationOverflowMenu(sections: overflow, selection: $selection, showLabels: showLabels)
                .foregroundStyle(selectedOverflow == nil ? AnyShapeStyle(.primary) : AnyShapeStyle(.tint))
                .background {
                    if selectedOverflow != nil {
                        Capsule().fill(.primary.opacity(0.08))
                    }
                }
                .padding(4)
                .glassCapsule()
                .accessibilityValue(selectedOverflow?.title ?? "")
                .accessibilityAddTraits(selectedOverflow == nil ? [] : .isSelected)
        }
    }
}

private struct NavigationOverflowBarSpaceModifier: ViewModifier {
    @Environment(\.navigationOverflowBarHeight) private var height

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            Color.clear
                .frame(height: height)
                .accessibilityHidden(true)
        }
    }
}
#endif

private struct NavigationOverflowBarHeightKey: EnvironmentKey {
    static let defaultValue: CGFloat = 0
}

extension EnvironmentValues {
    /// The height of the floating navigation bar over the bottom of a section, or 0.
    var navigationOverflowBarHeight: CGFloat {
        get { self[NavigationOverflowBarHeightKey.self] }
        set { self[NavigationOverflowBarHeightKey.self] = newValue }
    }
}

extension View {
    /// Keeps a navigation page's content and bottom controls above the floating
    /// navigation bar. A safe-area inset outside a navigation stack does not reach
    /// its pages, so every page of a section's stack applies this itself.
    func reservesNavigationOverflowBarSpace() -> some View {
        #if os(iOS)
        modifier(NavigationOverflowBarSpaceModifier())
        #else
        self
        #endif
    }
}
