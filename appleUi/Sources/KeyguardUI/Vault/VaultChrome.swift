import SwiftUI

#if os(macOS)
let menuBarHoistedActionIds: Set<String> = [
    "vaultList.sync",
    "vaultList.lock",
    "sendList.sync",
    "sendList.lock",
]
#endif

// MARK: - Shared action rendering

@ViewBuilder
func vaultActionMenuItems(
    _ actions: [VaultAction],
    invoke: @escaping @MainActor @Sendable (String) -> Void
) -> some View {
    ForEach(actions) { action in
        if action.startsSection {
            Divider()
        }
        switch action.role {
        case .toggleOn, .toggleOff:
            // A `Toggle` inside a `Menu` renders as a checkmark item on both platforms.
            Toggle(
                isOn: Binding(
                    get: { action.role == .toggleOn },
                    // `Binding`'s accessors are nonisolated; SwiftUI only ever runs
                    // them from the main actor, which is where `invoke` belongs.
                    set: { _ in MainActor.assumeIsolated { invoke(action.id) } }
                )
            ) {
                vaultActionLabel(action)
            }
        case .destructive:
            Button(role: .destructive) {
                invoke(action.id)
            } label: {
                vaultActionLabel(action)
            }
        case .normal:
            Button {
                invoke(action.id)
            } label: {
                vaultActionLabel(action)
            }
        }
    }
}

@ViewBuilder
func vaultActionLabel(_ action: VaultAction) -> some View {
    if let symbol = action.symbol {
        Label(action.title, systemImage: symbol)
    } else {
        Text(action.title)
    }
}

// MARK: - Toolbar overflow ("more")

/// The macOS toolbar overflow menu; iOS puts the same actions in `VaultListScreen`'s
/// ellipsis `Menu`.
struct VaultToolbarOverflowMenu: View {
    let actions: [VaultAction]
    let invoke: @MainActor @Sendable (String) -> Void

    var body: some View {
        Menu {
            vaultActionMenuItems(actions, invoke: invoke)
        } label: {
            Label(L10n.more, systemImage: "ellipsis.circle")
        }
        .help(L10n.more)
    }
}

// MARK: - Create menu

/// The "new item" create menu. The session already folds a paywalled create's
/// premium CTA into `createActions`.
struct VaultCreateMenu: View {
    let actions: [VaultAction]
    let disabled: Bool
    let invoke: @MainActor @Sendable (String) -> Void

    var body: some View {
        Menu {
            vaultActionMenuItems(actions, invoke: invoke)
        } label: {
            Label(L10n.vaultMainNewItemButton, systemImage: "plus")
        }
        .help(L10n.vaultMainCreateItemAction)
        .disabled(disabled || actions.isEmpty)
    }
}

// MARK: - Sort menu

/// Groups keyed by their `isSection` markers render as inline `Picker`s, for the
/// checkmark on the active row + the "selected" VoiceOver trait.
struct VaultSortMenuView: View {
    let menu: VaultSortMenu
    let invoke: @MainActor @Sendable (String) -> Void
    let clear: @MainActor @Sendable () -> Void

    var body: some View {
        Menu {
            if menu.canClear {
                Button(L10n.sortDefaultOrderTitle) { clear() }
                Divider()
            }
            ForEach(
                snapshotListSections(
                    menu.items, id: { $0.id },
                    sectionTitle: { $0.isSection ? $0.title : nil })
            ) { group in
                if let title = group.title {
                    Picker(
                        title,
                        selection: Binding(
                            get: { group.items.first(where: { $0.checked })?.id ?? "" },
                            set: { invoke($0) }
                        )
                    ) {
                        ForEach(group.items) { item in
                            Text(item.title).tag(item.id)
                        }
                    }
                    .pickerStyle(.inline)
                } else {
                    ForEach(group.items) { item in
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
        } label: {
            Label(L10n.sortAction, systemImage: "arrow.up.arrow.down")
        }
        .disabled(menu.items.isEmpty)
    }
}

// MARK: - Filters (toolbar menu form)

/// The collapsed toolbar form of the filter tree.
struct VaultFilterMenuView: View {
    let catalog: VaultFilterCatalog
    let state: VaultFilterState
    let invoke: @MainActor @Sendable (String) -> Void
    let clear: @MainActor @Sendable () -> Void
    let save: @MainActor @Sendable () -> Void

    var body: some View {
        Menu {
            if state.canClear {
                Button(L10n.filterClearAction, role: .destructive) { clear() }
                if state.canSave {
                    Button {
                        save()
                    } label: {
                        Label(L10n.customfiltersAddFilterTitle, systemImage: "plus.circle")
                    }
                }
                Divider()
            }
            // Only non-empty groups render in the menu (no expand/collapse here).
            ForEach(catalog.groups.filter { !$0.items.isEmpty }) { group in
                Section(group.title) {
                    if group.treeLayout, group.items.contains(where: { $0.parentNodeId != nil }) {
                        // Nested folder tree → native submenus per branch.
                        let children = Dictionary(
                            grouping: group.items.filter { $0.parentNodeId != nil },
                            by: { $0.parentNodeId ?? "" }
                        )
                        ForEach(group.items.filter { $0.parentNodeId == nil }) { chip in
                            VaultFilterMenuNode(
                                chip: chip,
                                children: children,
                                state: state,
                                invoke: invoke
                            )
                        }
                    } else {
                        ForEach(group.items) { chip in
                            Button {
                                invoke(chip.id)
                            } label: {
                                if state.checkedIds.contains(chip.id) {
                                    Label(chip.title, systemImage: "checkmark")
                                } else {
                                    Text(chip.title)
                                }
                            }
                            .disabled(!chip.selectable || !state.enabledIds.contains(chip.id))
                        }
                    }
                }
            }
        } label: {
            Label(
                L10n.filterListTitle,
                systemImage: state.activeCount > 0
                    ? "line.3.horizontal.decrease.circle.fill"
                    : "line.3.horizontal.decrease.circle"
            )
        }
        .disabled(catalog.groups.allSatisfy { $0.items.isEmpty })
    }
}

private struct VaultFilterMenuNode: View {
    let chip: VaultFilterChip
    /// All non-root chips of the group, keyed by `parentNodeId`.
    let children: [String: [VaultFilterChip]]
    let state: VaultFilterState
    let invoke: @MainActor @Sendable (String) -> Void

    var body: some View {
        let kids = chip.nodeId.flatMap { children[$0] } ?? []
        if kids.isEmpty {
            Button {
                invoke(chip.id)
            } label: {
                label
            }
            .disabled(!chip.selectable || !state.enabledIds.contains(chip.id))
        } else if chip.selectable && state.enabledIds.contains(chip.id) {
            Menu {
                submenu(kids)
            } label: {
                label
            } primaryAction: {
                invoke(chip.id)
            }
        } else {
            Menu {
                submenu(kids)
            } label: {
                label
            }
        }
    }

    private func submenu(_ kids: [VaultFilterChip]) -> some View {
        ForEach(kids) { kid in
            VaultFilterMenuNode(
                chip: kid,
                children: children,
                state: state,
                invoke: invoke
            )
        }
    }

    @ViewBuilder
    private var label: some View {
        if state.checkedIds.contains(chip.id) {
            Label(chip.title, systemImage: "checkmark")
        } else {
            Text(chip.title)
        }
    }
}

// MARK: - Filters (macOS sidebar form)

#if os(macOS)
struct VaultFilterSidebar: View, @MainActor Equatable {
    let catalog: VaultFilterCatalog
    let state: VaultFilterState
    /// Item count header; `nil` hides the counter (still loading).
    let count: Int?
    let invoke: @MainActor @Sendable (String) -> Void
    let toggleSection: @MainActor @Sendable (String) -> Void
    let clear: @MainActor @Sendable () -> Void
    let save: @MainActor @Sendable () -> Void

    static func == (lhs: VaultFilterSidebar, rhs: VaultFilterSidebar) -> Bool {
        lhs.catalog == rhs.catalog && lhs.state == rhs.state && lhs.count == rhs.count
    }

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
            ForEach(catalog.groups) { group in
                VStack(alignment: .leading, spacing: 6) {
                    if !group.title.isEmpty {
                        VaultFilterSectionHeader(group: group, toggle: toggleSection)
                    }
                    if !group.items.isEmpty {
                        if group.treeLayout {
                            // The canonical `List` layout (Misc always; Folder when
                            // nested folders exist): full-width rows, not a chip flow.
                            VaultFilterListSection(
                                group: group,
                                state: state,
                                invoke: invoke
                            )
                        } else {
                            FlowLayout(spacing: 4, clampsToWidth: true) {
                                ForEach(group.items) { chip in
                                    VaultFilterChipView(
                                        chip: chip,
                                        checked: state.checkedIds.contains(chip.id),
                                        enabled: state.enabledIds.contains(chip.id),
                                        invoke: invoke
                                    )
                                    .equatable()
                                }
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
            canClear: state.canClear,
            canSave: state.canSave,
            clear: clear,
            save: save
        )
    }
}

private struct VaultFilterSectionHeader: View {
    let group: VaultFilterGroup
    let toggle: @MainActor @Sendable (String) -> Void

    var body: some View {
        Button {
            toggle(group.sectionId)
        } label: {
            HStack(spacing: 4) {
                Text(group.title.uppercased())
                    .font(.caption.weight(.heavy))
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .rotationEffect(.degrees(group.collapsed ? 0 : 90))
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            group.collapsed ? L10n.filterExpandNamedAction(group.title) : L10n.filterCollapseNamedAction(group.title))
    }
}

private struct VaultFilterListSection: View {
    let group: VaultFilterGroup
    let state: VaultFilterState
    let invoke: @MainActor @Sendable (String) -> Void

    /// Expanded tree nodes. UI-local and default-collapsed by design — the
    /// Compose twin keeps it in a `remember(section.id)` map, never persisted.
    @State private var expandedNodeIds: Set<String> = []
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let nodes = Dictionary(
            group.items.compactMap { chip in chip.nodeId.map { ($0, chip) } },
            uniquingKeysWith: { first, _ in first }
        )
        VStack(alignment: .leading, spacing: 3) {
            ForEach(group.items) { chip in
                if isVisible(chip, nodes: nodes) {
                    VaultFilterListRow(
                        chip: chip,
                        checked: state.checkedIds.contains(chip.id),
                        enabled: state.enabledIds.contains(chip.id),
                        expanded: chip.expandable
                            ? chip.nodeId.map { expandedNodeIds.contains($0) }
                            : nil,
                        invoke: invoke,
                        toggleExpansion: { toggleExpansion(chip) }
                    )
                    // Every filter toggle re-runs this body; comparing the row's
                    // values keeps unchanged siblings from re-bodying.
                    .equatable()
                }
            }
        }
    }

    /// Mirrors the Compose `isVisible`: walk the ancestor chain (with a cycle
    /// guard); any collapsed ancestor hides the row.
    private func isVisible(_ chip: VaultFilterChip, nodes: [String: VaultFilterChip]) -> Bool {
        var parentId = chip.parentNodeId
        var visited = Set<String>()
        while let id = parentId, visited.insert(id).inserted {
            if !expandedNodeIds.contains(id) {
                return false
            }
            parentId = nodes[id]?.parentNodeId
        }
        return true
    }

    private func toggleExpansion(_ chip: VaultFilterChip) {
        guard let nodeId = chip.nodeId else { return }
        withAnimation(reduceMotion ? nil : .spring(duration: 0.25)) {
            if !expandedNodeIds.insert(nodeId).inserted {
                expandedNodeIds.remove(nodeId)
            }
        }
    }
}

private struct VaultFilterListRow: View, @MainActor Equatable {
    let chip: VaultFilterChip
    let checked: Bool
    let enabled: Bool
    /// Expansion state; `nil` when the row is not expandable (no chevron).
    let expanded: Bool?
    let invoke: @MainActor @Sendable (String) -> Void
    let toggleExpansion: @MainActor @Sendable () -> Void
    @Environment(\.accessibilityDifferentiateWithoutColor) private var differentiateWithoutColor

    /// Compares exactly the value data the body renders; the closures capture
    /// stable context and are excluded.
    static func == (lhs: VaultFilterListRow, rhs: VaultFilterListRow) -> Bool {
        lhs.chip == rhs.chip && lhs.checked == rhs.checked
            && lhs.enabled == rhs.enabled && lhs.expanded == rhs.expanded
    }

    /// Expand-only parents stay interactive (they open the branch) even when
    /// their id is not in `enabledIds`, mirroring `FilterNestedFolderItem`.
    private var interactive: Bool { enabled || expanded != nil }

    var body: some View {
        HStack(spacing: 0) {
            indent
            leadingSlot
            Button {
                if chip.selectable {
                    invoke(chip.id)
                } else {
                    toggleExpansion()
                }
            } label: {
                HStack(spacing: 0) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(chip.title)
                            .font(.subheadline)
                            .lineLimit(1)
                        if let text = chip.text, !text.isEmpty {
                            Text(text)
                                .font(.caption2)
                                .foregroundStyle(
                                    checked ? Color.accentColor.contrastingTextColor.opacity(0.8) : Color.secondary
                                )
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 3)
                .frame(minHeight: 28)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(chip.selectable && !enabled)
            .accessibilityAddTraits(checked ? .isSelected : [])
        }
        .padding(.horizontal, 8)
        .background(
            checked ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.clear),
            in: RoundedRectangle(cornerRadius: 8)
        )
        .foregroundStyle(checked ? Color.accentColor.contrastingTextColor : Color.primary)
        .overlay {
            RoundedRectangle(cornerRadius: 8)
                .strokeBorder(.primary, lineWidth: checked && differentiateWithoutColor ? 2 : 0)
        }
        .opacity(interactive ? 1 : 0.4)
        .disabled(!interactive)
    }

    /// The Compose indentation affordance: 16pt per level (capped at 5), a small
    /// dot marking the deepest level.
    @ViewBuilder
    private var indent: some View {
        let depth = Int(min(chip.depth, 5))
        if depth > 0 {
            HStack(spacing: 0) {
                ForEach(0..<depth, id: \.self) { level in
                    if level == depth - 1 {
                        Circle()
                            .fill(.quaternary)
                            .frame(width: 5, height: 5)
                            .frame(width: 16)
                    } else {
                        Color.clear
                            .frame(width: 16, height: 1)
                    }
                }
            }
        }
    }

    /// Fixed-width leading slot keeping titles aligned.
    @ViewBuilder
    private var leadingSlot: some View {
        Group {
            if let expanded {
                Button {
                    toggleExpansion()
                } label: {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                        .frame(width: 18, height: 18)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(
                    expanded ? L10n.filterCollapseNamedAction(chip.title) : L10n.filterExpandNamedAction(chip.title))
            } else if let symbol = chip.listRowSymbol {
                Image(systemName: symbol)
                    .font(.caption)
                    .foregroundStyle(checked ? Color.accentColor.contrastingTextColor : Color.secondary)
                    .frame(width: 18, height: 18)
            } else {
                Color.clear
                    .frame(width: 18, height: 18)
            }
        }
        .padding(.trailing, 6)
    }
}

private extension VaultFilterChip {
    var listRowSymbol: String? {
        if sectionId == "folder" {
            // In a tree-layout folder group the only nodeId-less row is "No folder".
            return nodeId == nil ? "folder.badge.minus" : "folder"
        }
        let filterSectionId = id.split(separator: "|").dropFirst().first.map(String.init)
        switch filterSectionId {
        case "misc.otp": return "timer"
        case "misc.attachments": return "paperclip"
        case "misc.passkeys": return "person.badge.key"
        case "misc.reprompt": return "lock"
        case "misc.sync": return "arrow.triangle.2.circlepath"
        case "misc.error": return "exclamationmark.triangle"
        case "misc.watchtower_alerts": return "shield"
        default: return nil
        }
    }
}
#endif

struct VaultFilterChipView: View, @MainActor Equatable {
    let chip: VaultFilterChip
    let checked: Bool
    let enabled: Bool
    let invoke: @MainActor @Sendable (String) -> Void

    static func == (lhs: VaultFilterChipView, rhs: VaultFilterChipView) -> Bool {
        lhs.chip == rhs.chip && lhs.checked == rhs.checked && lhs.enabled == rhs.enabled
    }

    var body: some View {
        FilterToggle(title: chip.title, subtitle: chip.text, isOn: checked) {
            invoke(chip.id)
        }
        .disabled(!enabled)
        .padding(.leading, CGFloat(chip.depth) * 12)
    }
}

/// The inline quick-filter chip flow rendered at the `.quickFilters` marker row,
/// mirroring the Compose `QuickFilters`.
struct VaultQuickFilterChips: View {
    let chips: [VaultFilterChip]
    let state: VaultFilterState
    let invoke: @MainActor @Sendable (String) -> Void

    var body: some View {
        if !chips.isEmpty {
            FlowLayout(spacing: 6, clampsToWidth: true) {
                ForEach(chips) { chip in
                    VaultFilterChipView(
                        chip: chip,
                        checked: state.checkedIds.contains(chip.id),
                        enabled: state.enabledIds.contains(chip.id),
                        invoke: invoke
                    )
                    .equatable()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 2)
        }
    }
}

// MARK: - Multi-selection bar

/// The floating bulk-action bar; shares its chrome with `SelectionActionBar` and
/// differs only in the action model (`VaultAction`).
struct VaultSelectionActionBar: View {
    let count: Int
    let actions: [VaultAction]
    let invoke: @MainActor @Sendable (String) -> Void
    let clear: @MainActor @Sendable () -> Void

    var body: some View {
        SelectionBarChrome(count: count, clear: clear, hasActions: !actions.isEmpty) {
            vaultActionMenuItems(actions, invoke: invoke)
        }
    }
}
