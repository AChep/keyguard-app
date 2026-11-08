import SwiftUI

#if os(iOS)
import UIKit

public struct KeyguardRootiOS: View {
    @Environment(AppPreferencesModel.self) private var preferencesModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var bottomBarHeight: CGFloat = 0
    @State private var keyboardVisible = false
    public init() {}

    public var body: some View {
        UnlockedNavigation { sections, selection, _ in
            let tabs = NavigationTab.items(for: sections)
            let overflow = tabs.filter(\.isOverflow).map(\.section)
            let isRegular = horizontalSizeClass == .regular
            // The bar would cover the focused field, so it steps aside for the keyboard.
            let showsBottomBar = !overflow.isEmpty && !isRegular && !keyboardVisible
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
                            // Each navigation page reserves the bottom bar's space itself.
                            .environment(\.navigationOverflowBarHeight, showsBottomBar ? bottomBarHeight : 0)
                            // Each navigation container must reserve the top bar's space.
                            .safeAreaInset(edge: .top, spacing: 0) {
                                if !overflow.isEmpty, isRegular {
                                    overflowBar(tabs: tabs, selection: selection)
                                }
                            }
                            .overlay(alignment: .bottom) {
                                if showsBottomBar {
                                    overflowBar(tabs: tabs, selection: selection)
                                        .onGeometryChange(for: CGFloat.self, of: { $0.size.height }) {
                                            bottomBarHeight = $0
                                        }
                                        .ignoresSafeArea(.keyboard)
                                }
                            }
                    }
                    // A selected tab must be visible, including when a deep link
                    // selects a destination that was previously in overflow.
                    .hidden(tab.isHidden(selectedKey: selection.wrappedValue))
                    // Keep the spoken title separate from the visible tab title.
                    .accessibilityLabel(Text(section.title))
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
                keyboardVisible = true
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                keyboardVisible = false
            }
        }
    }

    private func overflowBar(tabs: [NavigationTab], selection: Binding<String>) -> some View {
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
#endif
