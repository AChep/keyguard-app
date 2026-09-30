import SwiftUI
import KeyguardShared

enum SidebarLayout {
    static let width: CGFloat = 240

    static let listWidth: CGFloat = 240

    /// Minimum width of the flexible detail pane, shared by the Vault, Send,
    /// Settings and Watchtower columns. The detail pane stretches to fill whatever
    /// is left.
    static let detailMinWidth: CGFloat = 200

    /// Content width below which the filter sidebar collapses into a toolbar menu:
    /// the point where the fixed sidebar + fixed list + detail minimum no longer
    /// fit with a little slack for the split to give.
    static let filterCollapseThreshold: CGFloat = width + listWidth + detailMinWidth + 60
}

enum FilterSidebarMemory {
    private static let mainKey = "keyguard.filterSidebar.mainWide"
    private static let watchtowerKey = "keyguard.filterSidebar.watchtowerWide"

    /// Vault + Send (`MasterDetailLayout`) sidebar decision.
    static var mainWide: Bool {
        get { (UserDefaults.standard.object(forKey: mainKey) as? Bool) ?? true }
        set { UserDefaults.standard.set(newValue, forKey: mainKey) }
    }

    /// Watchtower (macOS) sidebar decision.
    static var watchtowerWide: Bool {
        get { (UserDefaults.standard.object(forKey: watchtowerKey) as? Bool) ?? true }
        set { UserDefaults.standard.set(newValue, forKey: watchtowerKey) }
    }
}

/// The left-hand filter column shown when the content area is wide enough.
struct FilterSidebar: View {
    let filters: [VaultFilterItemSnapshot]
    /// Number of items matching the current filter, shown as a header counter
    /// (mirroring the Compose filter pane). `nil` hides the counter — e.g. the
    /// Watchtower dashboard, which has no flat item list.
    let count: Int?
    let canClearFilters: Bool
    /// Whether the current selection can be saved as a named custom filter. When
    /// `true` a "Save filters" action sits beside "Clear filters". `nil` hides it
    /// (screens without a save affordance, e.g. Watchtower / SSH agent).
    var canSaveFilters: Bool = false
    let invoke: (String) -> Void
    let clear: () -> Void
    /// Saves the current filter selection as a named custom filter. The shared
    /// producer presents the name-entry dialog; defaults to a no-op for screens that
    /// pass `canSaveFilters: false`.
    var save: () -> Void = {}
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        List {
            if let count {
                Text(L10n.skippedItemsNote(count))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .contentTransition(reduceMotion ? .identity : .numericText())
                    .animation(reduceMotion ? nil : .default, value: count)
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 4, trailing: 8))
            }
            ForEach(sidebarFilterGroups(filters)) { group in
                VStack(alignment: .leading, spacing: 6) {
                    if let section = group.section {
                        FilterSectionHeader(section: section, invoke: invoke)
                    }
                    if !group.items.isEmpty {
                        FlowLayout(spacing: 4, clampsToWidth: true) {
                            ForEach(group.items, id: \.id) { item in
                                FilterChip(item: item, invoke: invoke)
                                    .equatable()
                            }
                        }
                    }
                }
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets(top: 4, leading: 8, bottom: 4, trailing: 8))
            }
        }
        .listStyle(.sidebar)
        .filterSidebarActions(
            canClear: canClearFilters,
            canSave: canSaveFilters,
            clear: clear,
            save: save
        )
    }

}

/// A full-width collapsible section header (Account, Type, Folder, …). Tapping it
/// forwards the section id to the producer, which toggles the collapsed state.
private struct FilterSectionHeader: View {
    let section: VaultFilterItemSnapshot
    let invoke: (String) -> Void

    var body: some View {
        Button {
            invoke(section.id)
        } label: {
            HStack(spacing: 4) {
                Text(section.title.uppercased())
                    .font(.caption.weight(.heavy))
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .rotationEffect(.degrees(section.expanded ? 90 : 0))
            }
            .padding(.vertical, 6)
            .touchTarget()
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!section.enabled)
        .accessibilityLabel(
            section.expanded
                ? L10n.filterCollapseNamedAction(section.title) : L10n.filterExpandNamedAction(section.title))
    }
}

/// Adapts a shared filter snapshot to the native toggle control.
private struct FilterChip: View, @MainActor Equatable {
    let item: VaultFilterItemSnapshot
    let invoke: (String) -> Void

    // The chip flow re-lays out on every filter-toggle emission; comparing the item
    // keeps that from re-bodying every sibling chip. (Mirrors `VaultFilterChipView`.)
    static func == (lhs: FilterChip, rhs: FilterChip) -> Bool {
        lhs.item == rhs.item
    }

    var body: some View {
        FilterToggle(title: item.title, subtitle: item.text, isOn: item.checked) {
            invoke(item.id)
        }
        .disabled(!item.enabled)
        .padding(.leading, CGFloat(Int(item.indent)) * 12)
    }
}

/// The collapsed toolbar form of the filter tree, grouping items into SwiftUI
/// `Section`s by their preceding SECTION header.
struct FilterMenu: View {
    let filters: [VaultFilterItemSnapshot]
    let canClearFilters: Bool
    var canSaveFilters: Bool = false
    let activeFilterCount: Int
    let invoke: (String) -> Void
    let clear: () -> Void
    var save: () -> Void = {}

    var body: some View {
        Menu {
            if canClearFilters {
                Button(L10n.filterClearAction, role: .destructive) {
                    clear()
                }
                if canSaveFilters {
                    Button {
                        save()
                    } label: {
                        Label(L10n.customfiltersAddFilterTitle, systemImage: "plus.circle")
                    }
                }
                Divider()
            }
            ForEach(menuFilterGroups(filters)) { group in
                Section(group.title) {
                    ForEach(group.items, id: \.id) { item in
                        Button {
                            invoke(item.id)
                        } label: {
                            if item.checked {
                                Label(item.title, systemImage: "checkmark")
                            } else {
                                Text(item.title)
                            }
                        }
                        .disabled(!item.enabled)
                    }
                }
            }
        } label: {
            Label(
                L10n.filterListTitle,
                systemImage: activeFilterCount > 0
                    ? "line.3.horizontal.decrease.circle.fill"
                    : "line.3.horizontal.decrease.circle"
            )
        }
        .disabled(filters.isEmpty)
    }
}

struct SortMenu: View {
    let sort: [VaultSortItemSnapshot]
    let canClearSort: Bool
    let invoke: (String) -> Void
    let clear: () -> Void

    var body: some View {
        Menu {
            if canClearSort {
                Button(L10n.sortDefaultOrderTitle) {
                    clear()
                }
                Divider()
            }
            ForEach(
                snapshotListSections(
                    sort, id: { $0.id },
                    sectionTitle: {
                        $0.kind == VaultSortItemKind.section ? $0.title : nil
                    })
            ) { group in
                sortPicker(group)
            }
        } label: {
            Label(L10n.sortAction, systemImage: "arrow.up.arrow.down")
        }
        .disabled(sort.isEmpty)
    }

    @ViewBuilder
    private func sortPicker(_ group: SnapshotListSection<VaultSortItemSnapshot>) -> some View {
        if let title = group.title {
            Picker(
                title,
                selection: Binding(
                    get: { group.items.first(where: { $0.checked })?.id ?? "" },
                    set: { invoke($0) }
                )
            ) {
                ForEach(group.items, id: \.id) { item in
                    Text(item.title).tag(item.id)
                }
            }
            .pickerStyle(.inline)
        } else {
            // An inline `Picker("")` reserves a blank header row for its empty
            // title — the large gap under the "Default order" divider — so the
            // leading, header-less group renders as plain checkmark buttons instead.
            ForEach(group.items, id: \.id) { item in
                Button {
                    invoke(item.id)
                } label: {
                    if item.checked {
                        Label(item.title, systemImage: "checkmark")
                    } else {
                        Text(item.title)
                    }
                }
            }
        }
    }
}

private struct FilterGroup: Identifiable {
    let id: String
    let title: String
    /// The section snapshot this group came from, or `nil` for the leading group
    /// of items that precede any SECTION header.
    let section: VaultFilterItemSnapshot?
    var items: [VaultFilterItemSnapshot]
}

/// Groups the flat filter list into sections keyed by their preceding SECTION
/// header, keeping every header — including collapsed (empty) ones — so the
/// sidebar can still show them as expandable rows.
private func sidebarFilterGroups(_ filters: [VaultFilterItemSnapshot]) -> [FilterGroup] {
    var result: [FilterGroup] = []
    var current = FilterGroup(id: "", title: "", section: nil, items: [])
    for item in filters {
        if item.kind == VaultFilterItemKind.section {
            if current.section != nil || !current.items.isEmpty {
                result.append(current)
            }
            current = FilterGroup(id: item.id, title: item.title, section: item, items: [])
        } else {
            current.items.append(item)
        }
    }
    if current.section != nil || !current.items.isEmpty {
        result.append(current)
    }
    return result
}

/// Groups the flat filter list for the toolbar menu, dropping empty groups since
/// the menu has no expand/collapse affordance.
private func menuFilterGroups(_ filters: [VaultFilterItemSnapshot]) -> [FilterGroup] {
    sidebarFilterGroups(filters).filter { !$0.items.isEmpty }
}
