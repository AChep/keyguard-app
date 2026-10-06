package com.artemchep.keyguard.apple.model

import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Combines the per-second [totpBadgeFlow]s into one `Map<itemId, TotpFieldSnapshot>` stream, so the TOTP
 * channels publish the live countdown without rebuilding their big snapshots.
 */
internal fun totpMapFlow(
    getTotpCode: GetTotpCodeWithOffset,
    items: List<Pair<String, TotpToken>>,
): Flow<Map<String, TotpFieldSnapshot>> {
    if (items.isEmpty()) return flowOf(emptyMap())
    val flows = items.map { (id, token) ->
        totpBadgeFlow(getTotpCode, token).map { id to it }
    }
    return combine(flows) { pairs -> pairs.toMap() }
}

internal suspend fun buildSelectionActionSnapshots(
    actions: List<ContextItem>?,
    leContext: LeContext,
    handlers: LinkedHashMap<String, () -> Unit>,
): List<VaultActionSnapshot> = actions.orEmpty()
    .filterIsInstance<FlatItemAction>()
    .toHeaderActionSnapshots("selection", leContext, handlers)

/**
 * Fingerprints rendered inputs for Recents, Quick Search and Duplicates, which
 * have no row cache. Badge trimming, shape and selection flags are folded into
 * the final row revision by the shared assembler.
 */
internal fun vaultItemFingerprint(item: VaultItem2.Item): Long {
    var hash = 0x9E3779B97F4A7C15uL.toLong()
    hash = hash * ROW_HASH_MULTIPLIER + item.source.id.hashCode()
    hash = hash * ROW_HASH_MULTIPLIER + item.revisionDate.hashCode()
    hash = hash * ROW_HASH_MULTIPLIER + item.title.text.hashCode()
    hash = hash * ROW_HASH_MULTIPLIER + (item.text?.hashCode() ?: 0)
    hash = hash * ROW_HASH_MULTIPLIER + (if (item.favourite) 1 else 0)
    hash = hash * ROW_HASH_MULTIPLIER + item.passwords.count { it.conceal }
    return hash
}

/**
 * [ContextItem.Section] markers are not emitted as rows; instead the first action after one carries
 * [VaultActionSnapshot.startsSection], mirroring the Compose section dividers. [idPrefix] keeps the ids
 * from colliding with the other action families on the same snapshot.
 */
internal suspend fun buildMenuActionSnapshots(
    actions: List<ContextItem>,
    idPrefix: String,
    leContext: LeContext,
    handlers: LinkedHashMap<String, () -> Unit>,
): List<VaultActionSnapshot> {
    val keys = ActionKeyAllocator(idPrefix)
    val acc = ArrayList<VaultActionSnapshot>()
    var pendingSection = false
    for (ci in actions) {
        when (ci) {
            // A leading section draws nothing; only an inter-group break matters.
            is ContextItem.Section -> if (acc.isNotEmpty()) pendingSection = true
            is FlatItemAction -> {
                val title = textResource(ci.title, leContext)
                val actionId = keys.keyFor(ci, title)
                ci.onClick?.let { handlers[actionId] = it }
                acc += VaultActionSnapshot(
                    id = actionId,
                    title = title,
                    isCopy = ci.type == FlatItemAction.Type.COPY,
                    startsSection = pendingSection,
                    switchState = ci.id?.toggleStateOrNull(),
                    danger = ci.danger,
                )
                pendingSection = false
            }

            is ContextItem.Custom -> Unit
        }
    }
    return acc
}

/**
 * Decodes the on/off state a switch-backed overflow action carries in its non-visual producer id
 * (e.g. `"vault.action.remember_sorting.true"`), like the Compose options menu's trailing `Switch`.
 * `null` for any other id.
 */
private fun String.toggleStateOrNull(): Boolean? = when {
    endsWith(".true") && startsWith("vault.action.") -> true
    endsWith(".false") && startsWith("vault.action.") -> false
    else -> null
}

internal fun mapFilterItemsToSnapshots(
    items: List<FilterItem>,
    handlers: LinkedHashMap<String, () -> Unit>,
): List<VaultFilterItemSnapshot> = items.map { item ->
    when (item) {
        is FilterItem.Section -> {
            item.onClick?.let { handlers[item.id] = it }
            VaultFilterItemSnapshot(
                id = item.id,
                kind = VaultFilterItemKind.SECTION,
                sectionId = item.sectionId,
                title = item.text,
                text = null,
                checked = false,
                enabled = item.onClick != null,
                expanded = item.expanded,
                indent = 0,
            )
        }

        is FilterItem.ChipItem -> {
            item.onClick?.let { handlers[item.id] = it }
            VaultFilterItemSnapshot(
                id = item.id,
                kind = VaultFilterItemKind.ITEM,
                sectionId = item.sectionId,
                title = item.title,
                text = item.text,
                checked = item.checked,
                enabled = item.enabled,
                expanded = true,
                indent = 0,
            )
        }

        is FilterItem.ListItem -> {
            item.onClick?.let { handlers[item.id] = it }
            VaultFilterItemSnapshot(
                id = item.id,
                kind = VaultFilterItemKind.ITEM,
                sectionId = item.sectionId,
                title = item.title,
                text = item.text,
                checked = item.checked,
                enabled = item.enabled,
                expanded = item.expandable,
                indent = item.depth,
            )
        }
    }
}

private const val ROW_HASH_MULTIPLIER = 31
