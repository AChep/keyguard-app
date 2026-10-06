import SwiftUI
import KeyguardShared

struct NavigationItemsSettingsView: View {
    @Environment(NavigationSettingsModel.self) private var navigationSettingsModel

    private var s: NavItemsSettingsSnapshot { navigationSettingsModel.navItemsSettings }

    var body: some View {
        Group {
            if !s.loaded {
                LoadingIndicator()
            } else {
                itemsList
            }
        }
        .navigationTitle(L10n.settingsNavigationItemsHeaderTitle)
        .toolbar {
            #if os(iOS)
            // iOS `List` drag-reorder only activates in edit mode; the per-row
            // menu's Move up / Move down stays the always-available path.
            ToolbarItem(placement: .topBarTrailing) {
                EditButton()
            }
            #endif
            ToolbarItem {
                Button {
                    navigationSettingsModel.resetNavItems()
                } label: {
                    Label(L10n.reset, systemImage: "arrow.counterclockwise")
                }
            }
        }
        .observing(
            start: { navigationSettingsModel.startNavItemsSettingsObservation() },
            stop: { navigationSettingsModel.stopNavItemsSettingsObservation() }
        )
    }

    private var itemsList: some View {
        List {
            Section {
                ForEach(s.items, id: \.key) { row in
                    itemRow(row)
                        .compactControlRowInsets()
                }
                .onMove { indices, newOffset in
                    var keys = s.items.map(\.key)
                    keys.move(fromOffsets: indices, toOffset: newOffset)
                    navigationSettingsModel.reorderNavItems(keys: keys)
                }
            }
            if !s.availableItems.isEmpty {
                Section {
                    Menu {
                        ForEach(s.availableItems, id: \.key) { row in
                            Button {
                                navigationSettingsModel.addNavItem(key: row.key)
                            } label: {
                                Label(row.title, systemImage: symbol(for: row))
                            }
                        }
                    } label: {
                        Label(L10n.navigationItemsAddItemTitle, systemImage: "plus")
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func itemRow(_ row: NavItemsSettingsRowSnapshot) -> some View {
        HStack(spacing: 12) {
            Label {
                Text(row.title)
                    .foregroundStyle(row.visible ? .primary : .secondary)
            } icon: {
                Image(systemName: symbol(for: row))
                    .foregroundStyle(row.visible ? Color.accentColor : Color.secondary)
            }
            Spacer()
            Toggle(
                isOn: Binding(
                    get: { row.visible },
                    set: { _ in navigationSettingsModel.toggleNavItemVisibility(key: row.key) }
                )
            ) {
                Text(row.title)
            }
            .labelsHidden()
            .disabled(!row.canToggleVisibility)
            Menu {
                Button {
                    navigationSettingsModel.moveNavItemUp(key: row.key)
                } label: {
                    Label(L10n.listMoveUp, systemImage: "chevron.up")
                }
                .disabled(!row.canMoveUp)
                Button {
                    navigationSettingsModel.moveNavItemDown(key: row.key)
                } label: {
                    Label(L10n.listMoveDown, systemImage: "chevron.down")
                }
                .disabled(!row.canMoveDown)
                if row.canRemove {
                    Divider()
                    Button(role: .destructive) {
                        navigationSettingsModel.removeNavItem(key: row.key)
                    } label: {
                        Label(L10n.listRemove, systemImage: "trash")
                    }
                }
            } label: {
                Label(L10n.moreActions, systemImage: "ellipsis.circle")
                    .labelStyle(.iconOnly)
                    .touchTarget()
            }
            #if os(macOS)
            .menuStyle(.borderlessButton)
            #endif
            .menuIndicator(.hidden)
            .fixedSize()
        }
    }

    /// A configured built-in row's key is `built_in:<key>`; map the suffix
    /// through the same symbol table the nav sections use. Filter rows carry
    /// a Kotlin icon hint.
    private func symbol(for row: NavItemsSettingsRowSnapshot) -> String {
        if row.isCipherFilter {
            return NavSection.symbol(forIconHint: row.iconHint)
        }
        let key =
            row.key.hasPrefix("built_in:")
            ? String(row.key.dropFirst("built_in:".count))
            : row.key
        return NavSection.builtInSymbol(key: key)
    }
}
