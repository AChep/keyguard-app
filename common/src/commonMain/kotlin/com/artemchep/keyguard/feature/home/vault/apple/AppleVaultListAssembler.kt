package com.artemchep.keyguard.feature.home.vault.apple

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.VAULT_QUICK_FILTERS_ID
import com.artemchep.keyguard.feature.home.vault.screen.VAULT_SECTION_ID_PREFIX

internal suspend fun assembleAppleVaultState(
    revision: Long,
    items: List<VaultItem2>,
    decorations: Map<String, AppleVaultRowDecoration>,
    itemCount: Int,
    includeQuickFilters: Boolean,
    passkeyTapKind: Int,
    fingerprintOf: (VaultItem2.Item) -> Long,
    translateSection: suspend (VaultItem2.Section) -> String,
    selectionOf: (VaultItem2.Item) -> Int = { 0 },
    canonicalRev: Int = 0,
    anchorId: String? = null,
    anchorOffset: Int = 0,
    anchorRevision: Int = 0,
): AppleVaultListState {
    val entries = ArrayList<AppleVaultEntry>(items.size + 1)
    val rows = HashMap<String, AppleVaultRowContent>(items.size * 2)
    if (includeQuickFilters) {
        // Mirrors the canonical producer inserting the QuickFilters marker row
        // at index 0 after trimming.
        val id = VAULT_QUICK_FILTERS_ID
        entries += AppleVaultEntry(id = id, kind = AppleVaultEntry.KIND_QUICK_FILTERS)
        rows[id] = appleMarkerRowContent(id, AppleVaultEntry.KIND_QUICK_FILTERS)
    }
    for (element in items) {
        when (element) {
            is VaultItem2.Item -> {
                val selectionFlags = selectionOf(element)
                val rev = appleRowContentRev(
                    fingerprint = fingerprintOf(element),
                    trimmedItem = element,
                    selectionFlags = selectionFlags,
                )
                entries += AppleVaultEntry(id = element.id, kind = AppleVaultEntry.KIND_ITEM)
                rows[element.id] = element.toAppleRowContent(
                    rev = rev,
                    passkeyTapKind = passkeyTapKind,
                    extraFlags = selectionFlags,
                )
            }

            is VaultItem2.Section -> {
                val id = VAULT_SECTION_ID_PREFIX + element.id
                val title = translateSection(element)
                entries += AppleVaultEntry(id = id, kind = AppleVaultEntry.KIND_SECTION)
                rows[id] = appleSectionRowContent(id, title)
            }

            is VaultItem2.NoItems -> {
                entries += AppleVaultEntry(id = element.id, kind = AppleVaultEntry.KIND_NO_ITEMS)
                rows[element.id] = appleMarkerRowContent(element.id, AppleVaultEntry.KIND_NO_ITEMS)
            }

            is VaultItem2.NoSuggestions -> {
                entries += AppleVaultEntry(id = element.id, kind = AppleVaultEntry.KIND_NO_SUGGESTIONS)
                rows[element.id] =
                    appleMarkerRowContent(element.id, AppleVaultEntry.KIND_NO_SUGGESTIONS)
            }

            // Never produced by the vault-list decoration path; the
            // QuickFilters marker is prepended above.
            is VaultItem2.QuickFilters -> Unit

            is VaultItem2.Button -> {
                entries += AppleVaultEntry(id = element.id, kind = AppleVaultEntry.KIND_BUTTON)
                rows[element.id] = appleButtonRowContent(
                    id = element.id,
                    title = element.title,
                )
            }
        }
    }
    val filteredDecorations = if (decorations.isEmpty()) {
        emptyMap()
    } else {
        decorations.filterKeys { it in rows }
    }

    val anchorOk = anchorId != null &&
            anchorRevision == canonicalRev &&
            rows.containsKey(anchorId)
    return AppleVaultListState(
        revision = revision,
        entries = entries,
        rows = rows,
        decorations = filteredDecorations,
        itemCount = itemCount,
        scrollAnchorId = if (anchorOk) anchorId.orEmpty() else "",
        scrollAnchorOffset = if (anchorOk) anchorOffset else 0,
    )
}

/** Assembles sibling surfaces with no search decorations or scroll anchor. */
suspend fun assembleSiblingAppleVaultState(
    revision: Long,
    items: List<VaultItem2>,
    mode: AppMode,
    fingerprintOf: (VaultItem2.Item) -> Long,
    translateSection: suspend (VaultItem2.Section) -> String = { "" },
    selectionOf: (VaultItem2.Item) -> Int = { 0 },
): AppleVaultListState = assembleAppleVaultState(
    revision = revision,
    items = items,
    decorations = emptyMap(),
    itemCount = items.count { it is VaultItem2.Item },
    includeQuickFilters = false,
    passkeyTapKind = applePasskeyTapKind(mode),
    fingerprintOf = fingerprintOf,
    translateSection = translateSection,
    selectionOf = selectionOf,
)
