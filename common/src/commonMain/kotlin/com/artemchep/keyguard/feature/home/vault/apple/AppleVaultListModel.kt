package com.artemchep.keyguard.feature.home.vault.apple

/** Bridge-safe data models for the Apple vault-list pipeline. */

/** One full keyed state of the list. */
data class AppleVaultListState(
    /** Monotonically increasing, bumped once per emission. */
    val revision: Long,
    /** The ordered structure of the list; content rides [rows]. */
    val entries: List<AppleVaultEntry>,
    /** Row content keyed by [AppleVaultEntry.id]; contains an entry for every id in [entries]. */
    val rows: Map<String, AppleVaultRowContent>,
    val decorations: Map<String, AppleVaultRowDecoration>,
    /** The number of cipher items (rows of kind [AppleVaultEntry.KIND_ITEM]) only. */
    val itemCount: Int,
    /** The id of the row the client should keep anchored on structure changes; `""` = none. */
    val scrollAnchorId: String,
    /** Pixel-ish offset of the anchor row; `0` when [scrollAnchorId] is `""`. */
    val scrollAnchorOffset: Int,
)

/** One structural slot of the list. */
data class AppleVaultEntry(
    /** Stable row id; preferred and section rows use prefixed ids. */
    val id: String,
    /** One of the `KIND_*` constants. */
    val kind: Int,
) {
    companion object {
        const val KIND_ITEM = 0
        const val KIND_SECTION = 1
        const val KIND_NO_ITEMS = 2
        const val KIND_NO_SUGGESTIONS = 3
        const val KIND_QUICK_FILTERS = 4

        const val KIND_BUTTON = 5
    }
}

/** Search-independent row content, fingerprinted by [rev]. */
data class AppleVaultRowContent(
    /** Same id scheme as [AppleVaultEntry.id]. */
    val id: String,
    /** Content fingerprint; changes iff any rendered field of this row changed. */
    val rev: Long,
    /** One of the [AppleVaultEntry] `KIND_*` constants. */
    val kind: Int,
    /** The underlying cipher id (differs from [id] for `preferred.` rows); `""` for non-item rows. */
    val secretId: String,
    /** The owning account id; `""` for non-item rows. */
    val accountId: String,
    val title: String,
    /** The type-specific second line (username, obscured card number, â¦); `""` if none. */
    val subtitle: String,
    /** An OR of the `FLAG_*` constants. */
    val flags: Int,
    /** SF Symbol name of the cipher-type fallback icon; `""` for non-item rows. */
    val typeSymbol: String,
    /** Favicon / app-icon URL; `""` when the row renders the initials / type icon. */
    val iconUrl: String,
    /** The short name-derived initials shown when [iconUrl] is empty or fails; `""` if none. */
    val iconInitials: String,
    val accentLightArgb: Int,
    val accentDarkArgb: Int,
    /** The owning organization's name; `""` for personal items. */
    val orgName: String,
    val orgAccentLightArgb: Int,
    val orgAccentDarkArgb: Int,
    /** Inline password / passkey / attachment badges, in render order. */
    val badges: List<AppleVaultRowBadge>,
    /** The `ShapeState` grouping bits (START / END / ALL / CENTER) of the row card. */
    val shapeState: Int,
) {
    companion object {
        const val FLAG_FAVOURITE = 1
        const val FLAG_REPROMPT = 1 shl 1
        const val FLAG_ATTACHMENTS = 1 shl 2
        const val FLAG_ERROR = 1 shl 3
        const val FLAG_HAS_TOTP = 1 shl 4
        const val FLAG_MULTILINE = 1 shl 5
        const val FLAG_CHEVRON = 1 shl 6

        /** The row is checked in a multi-selection surface. */
        const val FLAG_SELECTED = 1 shl 7

        /** The row can be toggled while multi-selection is active. */
        const val FLAG_SELECTING = 1 shl 8
    }
}

/** One inline badge of a cipher row. */
data class AppleVaultRowBadge(
    /** Stable within the row, e.g. `"password.0"`, `"passkey.<credentialId>"`. */
    val id: String,
    /** One of the `KIND_*` constants. */
    val kind: Int,
    /** Primary text (obscured password, passkey user name, attachment file name). */
    val text: String,
    /** Secondary text (passkey rpId, attachment human-readable size); `""` if none. */
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

/** Search-dependent row decoration. */
data class AppleVaultRowDecoration(
    /** Same id scheme as [AppleVaultEntry.id]. */
    val id: String,
    /** Matched-term bold ranges in the title, as flat `[start, end]` pairs. */
    val titleRanges: List<Int>,
    /** The matched-field context badge text (e.g. the matched note snippet); `""` if none. */
    val contextBadgeText: String,
    /** SF Symbol name of the matched field's icon; `""` if none. */
    val contextBadgeSymbol: String,
)

/** Data-only description of an action invoked by [id]. */
data class AppleVaultActionDescriptor(
    /** The stable `FlatItemAction.id` of the underlying action. */
    val id: String,
    val title: String,
    /** `""` if none. */
    val subtitle: String,
    /** SF Symbol name; `""` if none. */
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

data class AppleVaultHeader(
    /** The initial load has completed and [AppleVaultListState] is meaningful. */
    val loaded: Boolean,
    /** No account is attached; the client shows the add-account placeholder. */
    val needsAccount: Boolean,
    /** A sync is in flight; the client shows the refresh indicator. */
    val refreshing: Boolean,
    /** The current query text. */
    val query: String,
    val queryRevision: Int,
    val queryHighlighting: List<Int>,
    /** Programmatic cursor position; `-1` = leave the cursor alone. */
    val queryCursor: Int,
    /** The qualifier autocomplete suggestion label; `""` if none. */
    val qualifierSuggestion: String,
    /** The query text applied when the suggestion is accepted; `""` if none. */
    val qualifierSuggestionQuery: String,
    /** The "always show keyboard" preference: focus the search field on open. */
    val showKeyboard: Boolean,
    /** Adding items requires premium right now; the create action shows the paywall. */
    val paywalled: Boolean,
    /** The create-new-item menu. */
    val createActions: List<AppleVaultActionDescriptor>,
)

data class AppleVaultFilterCatalog(
    /** Bumped whenever the catalog itself (not the checked state) changes. */
    val revision: Long,
    val groups: List<AppleVaultFilterGroup>,
)

data class AppleVaultFilterGroup(
    val sectionId: String,
    val title: String,
    val collapsed: Boolean,
    /** The group renders as an indented tree (folders) rather than a chip flow. */
    val treeLayout: Boolean,
    val items: List<AppleVaultFilterChip>,
)

data class AppleVaultFilterChip(
    val id: String,
    val sectionId: String,
    val title: String,
    /** Secondary text (e.g. item count); `""` if none. */
    val text: String,
    /** SF Symbol name; `""` if none. */
    val symbol: String,
    /** Explicit tint (e.g. a collection color); `0` = default tint. */
    val tintLightArgb: Int,
    val tintDarkArgb: Int,
    /** Indentation depth in tree layout; `0` for flat chips. */
    val depth: Int,
    /** Tree node id; `""` for flat chips. */
    val nodeId: String,
    /** Parent tree node id; `""` for roots / flat chips. */
    val parentNodeId: String,
    /** The node has children and can expand / collapse. */
    val expandable: Boolean,
    val selectable: Boolean,
    /** The chip is the trailing "Apply / save filter" pseudo-chip. */
    val isApply: Boolean,
)

/** The cheap, frequently-changing part of the filter UI. */
data class AppleVaultFilterState(
    /** Bumped on every checked-state change. */
    val filterRevision: Int,
    val checkedIds: List<String>,
    val enabledIds: List<String>,
    val canClear: Boolean,
    val canSave: Boolean,
    /** The number of active filters (badge on the filter button). */
    val activeCount: Int,
)

data class AppleVaultSortMenu(
    val items: List<AppleVaultSortItem>,
    val canClear: Boolean,
    val visible: Boolean,
)

data class AppleVaultSortItem(
    val id: String,
    val title: String,
    /** SF Symbol name; `""` if none. */
    val symbol: String,
    val checked: Boolean,
    /** The item is a section label, not a selectable sort. */
    val isSection: Boolean,
)

/** The toolbar overflow ("more") menu plus the sync indicator. */
data class AppleVaultToolbar(
    val actions: List<AppleVaultActionDescriptor>,
    val syncing: Boolean,
)

/** The multi-selection bar; `count == 0` means no selection is active. */
data class AppleVaultSelection(
    val count: Int,
    val actions: List<AppleVaultActionDescriptor>,
    val selectedIds: Set<String> = emptySet(),
)

data class AppleVaultStructureOp(
    /** One of the `KIND_*` constants. */
    val kind: Int,
    val index: Int,
    val fromIndex: Int,
    val id: String,
) {
    companion object {
        const val KIND_INSERT = 0
        const val KIND_REMOVE = 1
        const val KIND_MOVE = 2
    }
}

data class AppleVaultDelta(
    val revision: Long,
    /** The revision this delta applies on top of; `-1` when [isFull]. */
    val baseRevision: Long,
    /** The structure is carried whole in [fullEntryIds]; [ops] is empty. */
    val isFull: Boolean,
    /** The client must drop ALL cached state (rows, decorations, anchors) first. */
    val isReset: Boolean,
    /** The complete new structure; empty unless [isFull]. */
    val fullEntryIds: List<AppleVaultEntry>,
    /** Structure ops, pre-sorted per the application-order contract; empty when [isFull]. */
    val ops: List<AppleVaultStructureOp>,
    /** Rows whose content is new or whose [AppleVaultRowContent.rev] changed. */
    val upserts: List<AppleVaultRowContent>,
    /** Row ids the client must drop from its content cache. */
    val removedIds: List<String>,
    val decorationUpserts: List<AppleVaultRowDecoration>,
    val decorationRemovedIds: List<String>,
    /** The client replaces its whole decoration map with [decorationUpserts]. */
    val decorationsReset: Boolean,
    val itemCount: Int,
    /** See [AppleVaultListState.scrollAnchorId]; `""` = none. */
    val scrollAnchorId: String,
    val scrollAnchorOffset: Int,
)

data class AppleVaultListConfig(
    /** The per-screen persistence namespace (sort / filter memory). */
    val persistenceScope: String,
    val appBarTitle: String,
    /** `""` if none. */
    val appBarSubtitle: String,
    /** Tri-state: [TRISTATE_ANY] / [TRISTATE_OFF] / [TRISTATE_ON]. */
    val trash: Int,
    /** Tri-state: [TRISTATE_ANY] / [TRISTATE_OFF] / [TRISTATE_ON]. */
    val archive: Int,
    /** Forces a fixed sort (e.g. the watchtower drill-downs); `""` = user-controlled. */
    val sortOverrideId: String,
    /** This is the main vault list (owns quick filters, remembers sorting, â¦). */
    val main: Boolean,
    /** The search also matches password values (the autofill pick screens). */
    val searchByPassword: Boolean,
    val canAddSecrets: Boolean,
    /** One of the `MODE_*` constants, mirroring the shared `AppMode` variants. */
    val mode: Int,
) {
    companion object {
        const val TRISTATE_ANY = -1
        const val TRISTATE_OFF = 0
        const val TRISTATE_ON = 1

        const val MODE_MAIN = 0
        const val MODE_QUICK_SEARCH = 1
        const val MODE_PICK = 2
        const val MODE_SAVE = 3
        const val MODE_PICK_PASSKEY = 4
        const val MODE_SAVE_PASSKEY = 5
        const val MODE_SAVE_PASSWORD = 6
    }
}
