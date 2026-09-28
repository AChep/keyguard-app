import SwiftUI

#if os(iOS)
public struct KeyguardRootiOS: View {
    @Environment(AppPreferencesModel.self) private var preferencesModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    public init() {}

    public var body: some View {
        UnlockedNavigation { sections, selection, _ in
            let tabs = NavigationTab.items(for: sections)
            let overflow = tabs.filter(\.isOverflow).map(\.section)
            TabView(selection: selection) {
                ForEach(tabs) { tab in
                    let section = tab.section
                    Tab(
                        preferencesModel.appPreferences.navLabel ? section.title : "",
                        systemImage: section.systemImage,
                        value: section.key
                    ) {
                        navSectionDetail(section)
                            .toolbar(overflow.isEmpty ? .automatic : .hidden, for: .tabBar)
                    }
                    // A selected tab must be visible, including when a deep link
                    // selects a destination that was previously in overflow.
                    .hidden(tab.isHidden(selectedKey: selection.wrappedValue))
                    // Keep the spoken title separate from the visible tab title.
                    .accessibilityLabel(Text(section.title))
                }
            }
            .safeAreaInset(edge: horizontalSizeClass == .regular ? .top : .bottom, spacing: 0) {
                if !overflow.isEmpty {
                    NavigationOverflowBar(
                        tabs: tabs,
                        selection: selection,
                        showLabels: preferencesModel.appPreferences.navLabel
                    )
                    .frame(maxWidth: 600)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
            }
        }
    }
}
#endif
