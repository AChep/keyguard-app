package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultActionDescriptor
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultFilterCatalog
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultFilterChip
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultFilterGroup
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultFilterState
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultHeader
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultRowBadge
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultRowContent
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultRowDecoration
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultSelection
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultSortItem
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultSortMenu
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultStructureOp
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultToolbar

/**
 * Mirrors `AppleVaultDelta`, with the `AppleVaultEntry` list split into the parallel
 * [fullEntryIds] / [fullEntryKinds] arrays for cheap bridging.
 *
 * Clients currently receive full structure frames; row content still crosses as deltas.
 * Revisions are monotonic within one unlock session and re-baseline after [isReset].
 */
data class VaultListDelta(
    val revision: Long,
    /** The revision this delta applies on top of; `-1` when [isFull]. */
    val baseRevision: Long,
    /** The structure is carried whole in [fullEntryIds]; [ops] is empty. */
    val isFull: Boolean,
    /**
     * Sent once when the vault locks: drop ALL cached state first (rows, decorations, anchors and
     * [revision]); the other fields are empty.
     */
    val isReset: Boolean,
    /** The complete new structure, in render order; empty unless [isFull]. */
    val fullEntryIds: List<String>,
    /** Parallel to [fullEntryIds]; `VaultRowSnapshot.KIND_*` values. */
    val fullEntryKinds: List<Int>,
    /** Structure ops reserved for incremental frames. */
    val ops: List<VaultStructureOpSnapshot>,
    /** Rows new to the client or whose [VaultRowSnapshot.rev] changed. */
    val upserts: List<VaultRowSnapshot>,
    /** Row ids the client must drop from its content cache. */
    val removedIds: List<String>,
    val decorationUpserts: List<VaultRowDecorationSnapshot>,
    val decorationRemovedIds: List<String>,
    /** The client replaces its whole decoration map with [decorationUpserts]. */
    val decorationsReset: Boolean,
    /** The number of cipher rows of the main list (sections excluded). */
    val itemCount: Int,
    /** The id of the row to keep anchored on structure changes; `""` = none. */
    val scrollAnchorId: String,
    val scrollAnchorOffset: Int,
)

data class VaultStructureOpSnapshot(
    /** One of the `KIND_*` constants. */
    val kind: Int,
    /**
     * For [KIND_REMOVE]: the index in the OLD list.
     * For [KIND_INSERT] / [KIND_MOVE]: the target index in the NEW list.
     */
    val index: Int,
    /** For [KIND_MOVE]: the index of [id] in the OLD list; `-1` otherwise. */
    val fromIndex: Int,
    val id: String,
) {
    companion object {
        const val KIND_INSERT = 0
        const val KIND_REMOVE = 1
        const val KIND_MOVE = 2
    }
}

/** The search-independent content of one row. */
data class VaultRowSnapshot(
    /** Same id scheme as the delta's `fullEntryIds` entries. */
    val id: String,
    /** Content fingerprint; changes iff any rendered field of this row changed. */
    val rev: Long,
    /** One of the `KIND_*` constants. */
    val kind: Int,
    /** The underlying cipher id (differs from [id] for `preferred.` rows); `""` for non-item rows. */
    val secretId: String,
    /** The owning account id; `""` for non-item rows. */
    val accountId: String,
    /** Plain title text — for sections, the section label. */
    val title: String,
    /** The type-specific second line; `""` if none. */
    val subtitle: String,
    /** An OR of the `FLAG_*` constants. */
    val flags: Int,
    /** SF Symbol name of the cipher-type fallback icon; `""` for non-item rows. */
    val typeSymbol: String,
    /** Favicon / app-icon URL; `""` when the row renders the initials / type icon. */
    val iconUrl: String,
    /** The initials shown when [iconUrl] is empty or fails; `""` if none. */
    val iconInitials: String,
    val accentLightArgb: Int,
    val accentDarkArgb: Int,
    /** The owning organization's name; `""` for personal items. */
    val orgName: String,
    val orgAccentLightArgb: Int,
    val orgAccentDarkArgb: Int,
    /** Inline password / passkey / attachment badges, in render order. */
    val badges: List<VaultRowBadgeSnapshot>,
    /** The `ShapeState` grouping bits of the row card. */
    val shapeState: Int,
) {
    companion object {
        // Row / entry kinds; mirror `AppleVaultEntry.KIND_*`. Shared by `kind` and
        // the delta's `fullEntryKinds`.
        const val KIND_ITEM = 0
        const val KIND_SECTION = 1
        const val KIND_NO_ITEMS = 2
        const val KIND_NO_SUGGESTIONS = 3
        const val KIND_QUICK_FILTERS = 4
        /** A full-width tappable button row (Duplicates' merge button); never emitted by the main vault list. */
        const val KIND_BUTTON = 5

        // Content flags; mirror `AppleVaultRowContent.FLAG_*`.
        const val FLAG_FAVOURITE = 1
        const val FLAG_REPROMPT = 1 shl 1
        const val FLAG_ATTACHMENTS = 1 shl 2
        const val FLAG_ERROR = 1 shl 3
        const val FLAG_HAS_TOTP = 1 shl 4
        const val FLAG_MULTILINE = 1 shl 5
        const val FLAG_CHEVRON = 1 shl 6
        // The per-row multi-selection display bits — set ONLY by the Duplicates
        // sibling surface (the main list projects selection out-of-band via the
        // selection channel).
        const val FLAG_SELECTED = 1 shl 7
        const val FLAG_SELECTING = 1 shl 8
    }
}

data class VaultRowBadgeSnapshot(
    /** Stable within the row, e.g. `"password.0"`, `"passkey.<credentialId>"`. */
    val id: String,
    /** One of the `KIND_*` constants. */
    val kind: Int,
    /** Primary text (obscured password, passkey user name, attachment file name). */
    val text: String,
    /** Secondary text (passkey rpId, attachment size); `""` if none. */
    val text2: String,
    /** What a tap on the badge does; one of the `TAP_*` constants. */
    val tapKind: Int,
) {
    companion object {
        const val KIND_PASSWORD = 0
        const val KIND_PASSKEY = 1
        const val KIND_ATTACHMENT = 2

        const val TAP_NONE = 0
        const val TAP_LARGE_TYPE = 1
        const val TAP_OPEN_PASSKEY = 2
        const val TAP_COMPLETE_PICK = 3
    }
}

/** The search-dependent decoration of one row. */
data class VaultRowDecorationSnapshot(
    val id: String,
    /** Matched-term bold ranges in the title, as flat `[start, end]` pairs. */
    val titleRanges: List<Int>,
    /** The matched-field context badge text; `""` if none. */
    val contextBadgeText: String,
    /** SF Symbol name of the matched field's icon; `""` if none. */
    val contextBadgeSymbol: String,
)

/**
 * Unlike the common model, [symbol] is FILLED here (from [VaultActionSymbols])
 * so the Swift menus render icons without their own id table.
 */
data class VaultActionDescriptorSnapshot(
    /** The stable `FlatItemAction.id`; dispatch through the session by this. */
    val id: String,
    val title: String,
    /** `""` if none. */
    val subtitle: String,
    /** SF Symbol name; `""` if the id has no mapping (logged loudly). */
    val symbol: String,
    /** One of the `ROLE_*` constants. */
    val role: Int,
    /** The client draws a section divider above this action. */
    val startsSection: Boolean,
    /** The action copies a value to the clipboard (clients may badge it). */
    val isCopy: Boolean,
) {
    companion object {
        const val ROLE_NORMAL = 0
        const val ROLE_DESTRUCTIVE = 1
        const val ROLE_TOGGLE_ON = 2
        const val ROLE_TOGGLE_OFF = 3
    }
}

data class VaultSessionHeaderSnapshot(
    val loaded: Boolean,
    val needsAccount: Boolean,
    val refreshing: Boolean,
    val query: String,
    /** Bumped on every programmatic query write (the QueryCell pattern). */
    val queryRevision: Int,
    /** Flat `[start, end, roleOrdinal]` triplets. */
    val queryHighlighting: List<Int>,
    /** Programmatic cursor position; `-1` = leave the cursor alone. */
    val queryCursor: Int,
    /** The qualifier autocomplete suggestion label; `""` if none. */
    val qualifierSuggestion: String,
    /** The query text applied when the suggestion is accepted; `""` if none. */
    val qualifierSuggestionQuery: String,
    val showKeyboard: Boolean,
    val paywalled: Boolean,
    val createActions: List<VaultActionDescriptorSnapshot>,
) {
    companion object {
        val empty = VaultSessionHeaderSnapshot(
            loaded = false,
            needsAccount = false,
            refreshing = false,
            query = "",
            queryRevision = 0,
            queryHighlighting = emptyList(),
            queryCursor = -1,
            qualifierSuggestion = "",
            qualifierSuggestionQuery = "",
            showKeyboard = false,
            paywalled = false,
            createActions = emptyList(),
        )
    }
}

data class VaultFilterCatalogSnapshot(
    /** Bumped whenever the catalog itself (not the checked state) changes. */
    val revision: Long,
    val groups: List<VaultFilterGroupSnapshot>,
) {
    companion object {
        val empty = VaultFilterCatalogSnapshot(
            revision = 0,
            groups = emptyList(),
        )
    }
}

data class VaultFilterGroupSnapshot(
    val sectionId: String,
    val title: String,
    val collapsed: Boolean,
    /** The group renders as an indented tree (folders) rather than a chip flow. */
    val treeLayout: Boolean,
    val items: List<VaultFilterChipSnapshot>,
)

data class VaultFilterChipSnapshot(
    val id: String,
    val sectionId: String,
    val title: String,
    /** Secondary text (e.g. item count); `""` if none. */
    val text: String,
    /** SF Symbol name; `""` if none. */
    val symbol: String,
    /** Explicit tint; `0` = default tint. */
    val tintLightArgb: Int,
    val tintDarkArgb: Int,
    /** Indentation depth in tree layout; `0` for flat chips. */
    val depth: Int,
    /** Tree node id; `""` for flat chips. */
    val nodeId: String,
    /** Parent tree node id; `""` for roots / flat chips. */
    val parentNodeId: String,
    val expandable: Boolean,
    /**
     * The chip carries a real filter toggle. `false` for expand-only tree
     * parents, whose tap should toggle expansion instead of invoking.
     */
    val selectable: Boolean,
    /** The chip is the trailing "Apply / save filter" pseudo-chip. */
    val isApply: Boolean,
)

/** The cheap, frequently-changing part of the filter UI. */
data class VaultFilterStateSnapshot(
    /** Bumped on every checked-state change. */
    val filterRevision: Int,
    val checkedIds: List<String>,
    val enabledIds: List<String>,
    val canClear: Boolean,
    val canSave: Boolean,
    /** The number of active filters (badge on the filter button). */
    val activeCount: Int,
) {
    companion object {
        val empty = VaultFilterStateSnapshot(
            filterRevision = 0,
            checkedIds = emptyList(),
            enabledIds = emptyList(),
            canClear = false,
            canSave = false,
            activeCount = 0,
        )
    }
}

data class VaultSessionSortSnapshot(
    val items: List<VaultSessionSortItemSnapshot>,
    val canClear: Boolean,
    val visible: Boolean,
) {
    companion object {
        val empty = VaultSessionSortSnapshot(
            items = emptyList(),
            canClear = false,
            visible = false,
        )
    }
}

data class VaultSessionSortItemSnapshot(
    val id: String,
    val title: String,
    /** SF Symbol name; `""` if none. */
    val symbol: String,
    val checked: Boolean,
    /** The item is a section label, not a selectable sort. */
    val isSection: Boolean,
)

data class VaultSessionToolbarSnapshot(
    val actions: List<VaultActionDescriptorSnapshot>,
    val syncing: Boolean,
) {
    companion object {
        val empty = VaultSessionToolbarSnapshot(
            actions = emptyList(),
            syncing = false,
        )
    }
}

/** `count == 0` = inactive. */
data class VaultSessionSelectionSnapshot(
    val count: Int,
    val actions: List<VaultActionDescriptorSnapshot>,
    val selectedIds: List<String> = emptyList(),
) {
    companion object {
        val empty = VaultSessionSelectionSnapshot(
            count = 0,
            actions = emptyList(),
        )
    }
}

// Mappers are internal because their receivers are non-exported :common types.

internal fun AppleVaultRowContent.toSnapshot(): VaultRowSnapshot = VaultRowSnapshot(
    id = id,
    rev = rev,
    kind = kind,
    secretId = secretId,
    accountId = accountId,
    title = title,
    subtitle = subtitle,
    flags = flags,
    typeSymbol = typeSymbol,
    iconUrl = iconUrl,
    iconInitials = iconInitials,
    accentLightArgb = accentLightArgb,
    accentDarkArgb = accentDarkArgb,
    orgName = orgName,
    orgAccentLightArgb = orgAccentLightArgb,
    orgAccentDarkArgb = orgAccentDarkArgb,
    badges = badges.map { it.toSnapshot() },
    shapeState = shapeState,
)

internal fun AppleVaultRowBadge.toSnapshot(): VaultRowBadgeSnapshot = VaultRowBadgeSnapshot(
    id = id,
    kind = kind,
    text = text,
    text2 = text2,
    tapKind = tapKind,
)

internal fun AppleVaultRowDecoration.toSnapshot(): VaultRowDecorationSnapshot = VaultRowDecorationSnapshot(
    id = id,
    titleRanges = titleRanges,
    contextBadgeText = contextBadgeText,
    contextBadgeSymbol = contextBadgeSymbol,
)

internal fun AppleVaultStructureOp.toSnapshot(): VaultStructureOpSnapshot = VaultStructureOpSnapshot(
    kind = kind,
    index = index,
    fromIndex = fromIndex,
    id = id,
)

internal fun AppleVaultActionDescriptor.toSnapshot(): VaultActionDescriptorSnapshot =
    VaultActionDescriptorSnapshot(
        id = id,
        title = title,
        subtitle = subtitle,
        // The common pipeline deliberately ships `""`; the id -> SF Symbol
        // mapping is an Apple-bridge concern.
        symbol = symbol.ifEmpty { VaultActionSymbols.symbolFor(id) },
        role = role,
        startsSection = startsSection,
        isCopy = isCopy,
    )

internal fun AppleVaultHeader.toSnapshot(): VaultSessionHeaderSnapshot = VaultSessionHeaderSnapshot(
    loaded = loaded,
    needsAccount = needsAccount,
    refreshing = refreshing,
    query = query,
    queryRevision = queryRevision,
    queryHighlighting = queryHighlighting,
    queryCursor = queryCursor,
    qualifierSuggestion = qualifierSuggestion,
    qualifierSuggestionQuery = qualifierSuggestionQuery,
    showKeyboard = showKeyboard,
    paywalled = paywalled,
    createActions = createActions.map { it.toSnapshot() },
)

internal fun AppleVaultFilterCatalog.toSnapshot(): VaultFilterCatalogSnapshot = VaultFilterCatalogSnapshot(
    revision = revision,
    groups = groups.map { it.toSnapshot() },
)

internal fun AppleVaultFilterGroup.toSnapshot(): VaultFilterGroupSnapshot = VaultFilterGroupSnapshot(
    sectionId = sectionId,
    title = title,
    collapsed = collapsed,
    treeLayout = treeLayout,
    items = items.map { it.toSnapshot() },
)

internal fun AppleVaultFilterChip.toSnapshot(): VaultFilterChipSnapshot = VaultFilterChipSnapshot(
    id = id,
    sectionId = sectionId,
    title = title,
    text = text,
    symbol = symbol,
    tintLightArgb = tintLightArgb,
    tintDarkArgb = tintDarkArgb,
    depth = depth,
    nodeId = nodeId,
    parentNodeId = parentNodeId,
    expandable = expandable,
    selectable = selectable,
    isApply = isApply,
)

internal fun AppleVaultFilterState.toSnapshot(): VaultFilterStateSnapshot = VaultFilterStateSnapshot(
    filterRevision = filterRevision,
    checkedIds = checkedIds,
    enabledIds = enabledIds,
    canClear = canClear,
    canSave = canSave,
    activeCount = activeCount,
)

internal fun AppleVaultSortMenu.toSnapshot(): VaultSessionSortSnapshot = VaultSessionSortSnapshot(
    items = items.map { it.toSnapshot() },
    canClear = canClear,
    visible = visible,
)

internal fun AppleVaultSortItem.toSnapshot(): VaultSessionSortItemSnapshot = VaultSessionSortItemSnapshot(
    id = id,
    title = title,
    symbol = symbol,
    checked = checked,
    isSection = isSection,
)

internal fun AppleVaultToolbar.toSnapshot(): VaultSessionToolbarSnapshot = VaultSessionToolbarSnapshot(
    actions = actions.map { it.toSnapshot() },
    syncing = syncing,
)

internal fun AppleVaultSelection.toSnapshot(): VaultSessionSelectionSnapshot = VaultSessionSelectionSnapshot(
    count = count,
    actions = actions.map { it.toSnapshot() },
    selectedIds = selectedIds.sorted(),
)
