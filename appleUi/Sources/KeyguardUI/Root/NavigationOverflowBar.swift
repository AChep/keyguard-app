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
                        .foregroundStyle(selection == tab.id ? Color.accentColor : .primary)
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
                .foregroundStyle(selectedOverflow == nil ? Color.primary : .accentColor)
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
#endif
