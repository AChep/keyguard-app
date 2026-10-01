import Foundation
import KeyguardShared
import OSLog

// MARK: - Row vocabulary

/// Row / entry kind; raw values mirror `VaultRowSnapshot.KIND_*`.
enum VaultRowKind: Int32, Equatable, Hashable, Sendable {
    case item = 0
    case section = 1
    case noItems = 2
    case noSuggestions = 3
    case quickFilters = 4
    /// A full-width tappable button row (e.g. Duplicates' merge button); never
    /// emitted by the main vault list.
    case button = 5

    /// Maps a bridged raw kind, falling back to `.item` (and logging loudly) on
    /// an unknown value so a Kotlin-side vocabulary bump never crashes the list.
    static func from(_ raw: Int32) -> VaultRowKind {
        if let kind = VaultRowKind(rawValue: raw) { return kind }
        vaultLog("unknown row kind \(raw) — falling back to .item")
        return .item
    }
}

/// One structure entry: the id + kind of one rendered row.
struct VaultRowEntry: Equatable, Hashable, Sendable {
    let id: String
    let kind: VaultRowKind
}

/// The row content flags; mirrors `VaultRowSnapshot.FLAG_*`.
struct VaultRowFlags: OptionSet, Equatable, Sendable {
    let rawValue: Int32

    static let favourite = VaultRowFlags(rawValue: 1 << 0)
    static let reprompt = VaultRowFlags(rawValue: 1 << 1)
    static let attachments = VaultRowFlags(rawValue: 1 << 2)
    static let error = VaultRowFlags(rawValue: 1 << 3)
    static let hasTotp = VaultRowFlags(rawValue: 1 << 4)
    static let multiline = VaultRowFlags(rawValue: 1 << 5)
    /// The row is part of an active multi-selection (drawn checked). Set ONLY by
    /// the Duplicates surface; the main list projects selection out-of-band.
    static let selected = VaultRowFlags(rawValue: 1 << 7)
    /// A multi-selection is active and this row shows a selection affordance.
    /// Same Duplicates-only contract as `.selected`.
    static let selecting = VaultRowFlags(rawValue: 1 << 8)
}

/// One inline badge of a cipher row; `Kind` raw values mirror `VaultRowBadgeSnapshot.KIND_*`.
struct VaultRowBadge: Equatable, Sendable, Identifiable {
    enum Kind: Int32, Equatable, Sendable {
        case password = 0
        case passkey = 1
        case attachment = 2
    }

    /// Stable within the row (e.g. `"password.0"`); taps dispatch through
    /// `VaultListSession.performVaultBadgeTap(rowId:badgeId:)` by this id.
    let id: String
    let kind: Kind
    /// Primary text (obscured password, passkey user name, attachment file name).
    let text: String
    /// Secondary text (passkey rpId, attachment size).
    let text2: String?

    init(bridged: VaultRowBadgeSnapshot) {
        id = bridged.id
        kind =
            Kind(rawValue: bridged.kind)
            ?? {
                vaultLog("unknown badge kind \(bridged.kind) for '\(bridged.id)' — falling back to .password")
                return .password
            }()
        text = bridged.text
        text2 = bridged.text2.nilIfEmpty
    }
}

/// The search-independent content of one row.
struct VaultRow: Equatable, Sendable {
    let id: String
    /// Content fingerprint; changes iff any rendered field of this row changed.
    let rev: Int64
    let kind: VaultRowKind
    /// The underlying cipher id (differs from `id` for `preferred.` rows); `nil` for non-item rows.
    let secretId: String?
    /// The owning account id; `nil` for non-item rows.
    let accountId: String?
    /// Plain title text — for sections, the section label.
    let title: String
    /// The type-specific second line.
    let subtitle: String?
    let flags: VaultRowFlags
    /// SF Symbol name of the cipher-type fallback icon; `nil` for non-item rows.
    let typeSymbol: String?
    /// Favicon / app-icon URL; `nil` when the row renders the initials / type icon.
    let iconUrl: String?
    /// The initials shown when `iconUrl` is `nil` or fails.
    let iconInitials: String?
    /// Packed ARGB accents; `0` = no accent.
    let accentLightArgb: Int32
    let accentDarkArgb: Int32
    /// The owning organization's name; `nil` for personal items.
    let orgName: String?
    let orgAccentLightArgb: Int32
    let orgAccentDarkArgb: Int32
    /// Inline password / passkey / attachment badges, in render order.
    let badges: [VaultRowBadge]

    init(bridged: VaultRowSnapshot) {
        id = bridged.id
        rev = bridged.rev
        kind = VaultRowKind.from(bridged.kind)
        secretId = bridged.secretId.nilIfEmpty
        accountId = bridged.accountId.nilIfEmpty
        title = bridged.title
        subtitle = bridged.subtitle.nilIfEmpty
        flags = VaultRowFlags(rawValue: bridged.flags)
        typeSymbol = bridged.typeSymbol.nilIfEmpty
        iconUrl = bridged.iconUrl.nilIfEmpty
        iconInitials = bridged.iconInitials.nilIfEmpty
        accentLightArgb = bridged.accentLightArgb
        accentDarkArgb = bridged.accentDarkArgb
        orgName = bridged.orgName.nilIfEmpty
        orgAccentLightArgb = bridged.orgAccentLightArgb
        orgAccentDarkArgb = bridged.orgAccentDarkArgb
        badges = bridged.badges.map(VaultRowBadge.init(bridged:))
    }
}

/// The search-dependent decoration of one row.
struct VaultRowDecoration: Equatable, Sendable {
    let id: String
    /// Matched-term bold ranges in the title, indexing its UTF-16 code units.
    let titleRanges: [Range<Int>]
    /// The matched-field context badge text.
    let contextBadgeText: String?
    /// SF Symbol name of the matched field's icon.
    let contextBadgeSymbol: String?

    init(bridged: VaultRowDecorationSnapshot) {
        id = bridged.id
        titleRanges = unpackRangePairs(bridged.titleRanges, what: "titleRanges of '\(bridged.id)'")
        contextBadgeText = bridged.contextBadgeText.nilIfEmpty
        contextBadgeSymbol = bridged.contextBadgeSymbol.nilIfEmpty
    }
}

// MARK: - Actions

/// A data-only description of one menu or bar action; `Role` raw values mirror
/// `VaultActionDescriptorSnapshot.ROLE_*`.
struct VaultAction: Equatable, Sendable, Identifiable {
    enum Role: Int32, Equatable, Sendable {
        case normal = 0
        case destructive = 1
        case toggleOn = 2
        case toggleOff = 3
    }

    /// The stable `FlatItemAction.id`; dispatch through the session by this.
    let id: String
    let title: String
    let subtitle: String?
    /// SF Symbol name; `nil` if the id has no mapping (already logged loudly
    /// Kotlin-side by `VaultActionSymbols`).
    let symbol: String?
    let role: Role
    /// The client draws a section divider above this action.
    let startsSection: Bool
    /// The action copies a value to the clipboard (clients may badge it).
    let isCopy: Bool

    /// Synthesizes an action Swift-side, for sibling surfaces (Recents, Duplicates)
    /// whose row menu is defined in Swift rather than projected from a Kotlin
    /// descriptor. The main list always uses `init(bridged:)`.
    init(
        id: String,
        title: String,
        subtitle: String? = nil,
        symbol: String? = nil,
        role: Role = .normal,
        startsSection: Bool = false,
        isCopy: Bool = false
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.symbol = symbol
        self.role = role
        self.startsSection = startsSection
        self.isCopy = isCopy
    }

    init(bridged: VaultActionDescriptorSnapshot) {
        id = bridged.id
        title = bridged.title
        subtitle = bridged.subtitle.nilIfEmpty
        symbol = bridged.symbol.nilIfEmpty
        role =
            Role(rawValue: bridged.role)
            ?? {
                vaultLog("unknown action role \(bridged.role) for '\(bridged.id)' — falling back to .normal")
                return .normal
            }()
        startsSection = bridged.startsSection
        isCopy = bridged.isCopy
    }
}

// MARK: - Header

/// The chrome above the list.
struct VaultHeader: Equatable, Sendable {
    let loaded: Bool
    let needsAccount: Bool
    let query: String
    /// Bumped on every programmatic query write (the QueryCell / `bridgedText`
    /// revision protocol).
    let queryRevision: Int32
    /// The qualifier autocomplete suggestion label.
    let qualifierSuggestion: String?
    let createActions: [VaultAction]

    static let empty = VaultHeader(
        loaded: false,
        needsAccount: false,
        query: "",
        queryRevision: 0,
        qualifierSuggestion: nil,
        createActions: []
    )

    init(
        loaded: Bool,
        needsAccount: Bool,
        query: String,
        queryRevision: Int32,
        qualifierSuggestion: String?,
        createActions: [VaultAction]
    ) {
        self.loaded = loaded
        self.needsAccount = needsAccount
        self.query = query
        self.queryRevision = queryRevision
        self.qualifierSuggestion = qualifierSuggestion
        self.createActions = createActions
    }

    init(bridged: VaultSessionHeaderSnapshot) {
        loaded = bridged.loaded
        needsAccount = bridged.needsAccount
        query = bridged.query
        queryRevision = bridged.queryRevision
        qualifierSuggestion = bridged.qualifierSuggestion.nilIfEmpty
        createActions = bridged.createActions.map(VaultAction.init(bridged:))
    }
}

// MARK: - Filters

struct VaultFilterChip: Equatable, Sendable, Identifiable {
    let id: String
    let sectionId: String
    let title: String
    /// Secondary text (e.g. item count).
    let text: String?
    /// SF Symbol name.
    let symbol: String?
    /// Indentation depth in tree layout; `0` for flat chips.
    let depth: Int32
    /// Tree node id; `nil` for flat chips.
    let nodeId: String?
    /// Parent tree node id; `nil` for roots / flat chips.
    let parentNodeId: String?
    let expandable: Bool
    /// The chip carries a real filter toggle; `false` for expand-only tree
    /// parents, whose tap toggles expansion instead of invoking.
    let selectable: Bool

    init(bridged: VaultFilterChipSnapshot) {
        id = bridged.id
        sectionId = bridged.sectionId
        title = bridged.title
        text = bridged.text.nilIfEmpty
        symbol = bridged.symbol.nilIfEmpty
        depth = bridged.depth
        nodeId = bridged.nodeId.nilIfEmpty
        parentNodeId = bridged.parentNodeId.nilIfEmpty
        expandable = bridged.expandable
        selectable = bridged.selectable
    }
}

/// One filter section.
struct VaultFilterGroup: Equatable, Sendable, Identifiable {
    var id: String { sectionId }
    let sectionId: String
    let title: String
    let collapsed: Bool
    /// The group renders as an indented tree (folders) rather than a chip flow.
    let treeLayout: Bool
    let items: [VaultFilterChip]

    init(bridged: VaultFilterGroupSnapshot) {
        sectionId = bridged.sectionId
        title = bridged.title
        collapsed = bridged.collapsed
        treeLayout = bridged.treeLayout
        items = bridged.items.map(VaultFilterChip.init(bridged:))
    }
}

/// The full filter tree.
struct VaultFilterCatalog: Equatable, Sendable {
    /// Bumped whenever the catalog itself (not the checked state) changes.
    let revision: Int64
    let groups: [VaultFilterGroup]

    static let empty = VaultFilterCatalog(revision: 0, groups: [])

    init(revision: Int64, groups: [VaultFilterGroup]) {
        self.revision = revision
        self.groups = groups
    }

    init(bridged: VaultFilterCatalogSnapshot) {
        revision = bridged.revision
        groups = bridged.groups.map(VaultFilterGroup.init(bridged:))
    }
}

/// The cheap, frequently-changing part of the filter UI. The id lists become
/// `Set`s — membership checks (`isChecked` / `isEnabled` per chip) are the only reads.
struct VaultFilterState: Equatable, Sendable {
    /// Bumped on every checked-state change.
    let filterRevision: Int32
    let checkedIds: Set<String>
    let enabledIds: Set<String>
    let canClear: Bool
    let canSave: Bool
    /// The number of active filters (badge on the filter button).
    let activeCount: Int32

    static let empty = VaultFilterState(
        filterRevision: 0,
        checkedIds: [],
        enabledIds: [],
        canClear: false,
        canSave: false,
        activeCount: 0
    )

    init(
        filterRevision: Int32,
        checkedIds: Set<String>,
        enabledIds: Set<String>,
        canClear: Bool,
        canSave: Bool,
        activeCount: Int32
    ) {
        self.filterRevision = filterRevision
        self.checkedIds = checkedIds
        self.enabledIds = enabledIds
        self.canClear = canClear
        self.canSave = canSave
        self.activeCount = activeCount
    }

    init(bridged: VaultFilterStateSnapshot) {
        filterRevision = bridged.filterRevision
        checkedIds = Set(bridged.checkedIds)
        enabledIds = Set(bridged.enabledIds)
        canClear = bridged.canClear
        canSave = bridged.canSave
        activeCount = bridged.activeCount
    }
}

// MARK: - Sort / toolbar / selection

/// One sort menu entry.
struct VaultSortItem: Equatable, Sendable, Identifiable {
    let id: String
    let title: String
    /// SF Symbol name.
    let symbol: String?
    let checked: Bool
    /// The item is a section label, not a selectable sort.
    let isSection: Bool

    init(bridged: VaultSessionSortItemSnapshot) {
        id = bridged.id
        title = bridged.title
        symbol = bridged.symbol.nilIfEmpty
        checked = bridged.checked
        isSection = bridged.isSection
    }
}

struct VaultSortMenu: Equatable, Sendable {
    let items: [VaultSortItem]
    let canClear: Bool
    let visible: Bool

    static let empty = VaultSortMenu(items: [], canClear: false, visible: false)

    init(items: [VaultSortItem], canClear: Bool, visible: Bool) {
        self.items = items
        self.canClear = canClear
        self.visible = visible
    }

    init(bridged: VaultSessionSortSnapshot) {
        items = bridged.items.map(VaultSortItem.init(bridged:))
        canClear = bridged.canClear
        visible = bridged.visible
    }
}

/// The toolbar overflow menu plus the sync indicator.
struct VaultToolbar: Equatable, Sendable {
    let actions: [VaultAction]
    let syncing: Bool

    static let empty = VaultToolbar(actions: [], syncing: false)

    init(actions: [VaultAction], syncing: Bool) {
        self.actions = actions
        self.syncing = syncing
    }

    init(bridged: VaultSessionToolbarSnapshot) {
        actions = bridged.actions.map(VaultAction.init(bridged:))
        syncing = bridged.syncing
    }
}

/// The multi-selection bar; `count == 0` = inactive.
struct VaultSelection: Equatable, Sendable {
    let count: Int
    let actions: [VaultAction]
    /// Cipher identities captured by the producer's bulk actions, not native row ids.
    let selectedIds: Set<String>

    static let empty = VaultSelection(count: 0, actions: [])

    init(count: Int, actions: [VaultAction], selectedIds: Set<String> = []) {
        self.count = count
        self.actions = actions
        self.selectedIds = selectedIds
    }

    init(bridged: VaultSessionSelectionSnapshot) {
        count = Int(bridged.count)
        actions = bridged.actions.map(VaultAction.init(bridged:))
        selectedIds = Set(bridged.selectedIds)
    }

    /// A contextual click outside the selection always acts on its own item.
    func contextualItemIds(for itemId: String?, minimumCount: Int) -> Set<String>? {
        guard let itemId, count >= minimumCount, selectedIds.count == count,
            selectedIds.contains(itemId)
        else { return nil }
        return selectedIds
    }
}

// MARK: - Delta

struct VaultDelta: Sendable {
    let revision: Int64
    let isFull: Bool
    /// Drop ALL cached state first; the other fields are empty.
    let isReset: Bool
    /// The complete new structure, in render order; empty unless `isFull`.
    let entries: [VaultRowEntry]
    /// The bridged delta carried structure ops; this client does not apply them.
    let hasOps: Bool
    /// Rows new to the client or whose `rev` changed.
    let upserts: [VaultRow]
    /// Row ids the client must drop from its content cache.
    let removedIds: [String]
    let decorationUpserts: [VaultRowDecoration]
    let decorationRemovedIds: [String]
    /// The client replaces its whole decoration map with `decorationUpserts`.
    let decorationsReset: Bool
    /// The number of cipher rows of the main list (sections excluded).
    let itemCount: Int
    /// The id of the row to keep anchored on structure changes.
    let scrollAnchorId: String?
    let scrollAnchorOffset: Int

    /// Converts the bridged ObjC delta into pure Swift values. Runs on the
    /// session's BACKGROUND delivery thread by design — this is the heavy part
    /// the one-hop-to-Main contract keeps off the main thread.
    init(bridged: VaultListDelta) {
        revision = bridged.revision
        isFull = bridged.isFull
        isReset = bridged.isReset
        let ids = bridged.fullEntryIds
        let kinds = bridged.fullEntryKinds
        if ids.count != kinds.count {
            vaultLog(
                "delta rev \(bridged.revision): fullEntryIds (\(ids.count)) and "
                    + "fullEntryKinds (\(kinds.count)) are not parallel — truncating to the shorter"
            )
        }
        entries = zip(ids, kinds).map { id, kind in
            VaultRowEntry(id: id, kind: VaultRowKind.from(kind.int32Value))
        }
        hasOps = !bridged.ops.isEmpty
        upserts = bridged.upserts.map(VaultRow.init(bridged:))
        removedIds = bridged.removedIds
        decorationUpserts = bridged.decorationUpserts.map(VaultRowDecoration.init(bridged:))
        decorationRemovedIds = bridged.decorationRemovedIds
        decorationsReset = bridged.decorationsReset
        itemCount = Int(bridged.itemCount)
        scrollAnchorId = bridged.scrollAnchorId.nilIfEmpty
        scrollAnchorOffset = Int(bridged.scrollAnchorOffset)
    }
}

// MARK: - Conversion helpers

private extension String {
    /// The Kotlin bridge's `""` absent-string sentinel, back to a Swift optional.
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

/// Unpacks a bridged flat `[start, end]` pair list into `Range<Int>`s, skipping
/// (and loudly logging) malformed pairs so a bad span never crashes rendering.
private func unpackRangePairs(_ flat: [KotlinInt], what: String) -> [Range<Int>] {
    if flat.count % 2 != 0 {
        vaultLog("\(what): odd flat range list (\(flat.count) values) — dropping the tail")
    }
    var ranges: [Range<Int>] = []
    ranges.reserveCapacity(flat.count / 2)
    var index = 0
    while index + 1 < flat.count {
        let start = Int(truncating: flat[index])
        let end = Int(truncating: flat[index + 1])
        if start >= 0 && start < end {
            ranges.append(start..<end)
        } else {
            vaultLog("\(what): malformed range [\(start), \(end)) — skipping")
        }
        index += 2
    }
    return ranges
}

// MARK: - Logging

private let vaultLogger = Logger(subsystem: "com.artemchep.keyguard", category: "VaultList")

func vaultLog(_ message: @autoclosure () -> String) {
    let message = message()
    vaultLogger.warning("\(message)")
}
