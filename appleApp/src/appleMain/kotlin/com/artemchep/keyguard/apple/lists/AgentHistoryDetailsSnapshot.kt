package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.apple.model.VaultReferenceSnapshot
import com.artemchep.keyguard.feature.agent.history.AgentHistoryDetails
import com.artemchep.keyguard.feature.navigation.RouteDescriptor
import com.artemchep.keyguard.feature.navigation.toDeepLink

/**
 * The details sheet of one agent request log row. [icon] is the
 * [com.artemchep.keyguard.feature.agent.history.AgentHistoryDetailsIcon] name and
 * [outcome] the [com.artemchep.keyguard.feature.agent.history.AgentHistoryOutcome] name.
 */
data class AgentHistoryDetailsSnapshot(
    val icon: String,
    val title: String,
    val outcome: String,
    val outcomeText: String,
    val date: String,
    val sections: List<AgentHistoryDetailsSectionSnapshot>,
)

/** [title] is `null` if the rows speak for themselves. */
data class AgentHistoryDetailsSectionSnapshot(
    val id: String,
    val title: String?,
    val rows: List<AgentHistoryDetailsRowSnapshot>,
)

enum class AgentHistoryDetailsRowKind {
    /** A [label] and its [value]. */
    TEXT,

    /** A long [value], e.g. a command line, shown in full. */
    CODE,

    /** The vault item the request used, in [reference]. */
    REFERENCE,
}

/**
 * [icon] is the [com.artemchep.keyguard.feature.agent.history.AgentHistoryDetailsIcon] name,
 * empty if the row has none.
 * The [reference]'s `clickActionId` is the `keyguard://` deep link of the item, `null`
 * if it no longer exists.
 */
data class AgentHistoryDetailsRowSnapshot(
    val id: String,
    val kind: AgentHistoryDetailsRowKind,
    val icon: String,
    val label: String,
    val value: String,
    val monospace: Boolean,
    val reference: VaultReferenceSnapshot?,
)

internal fun AgentHistoryDetails.toSnapshot() = AgentHistoryDetailsSnapshot(
    icon = icon.name,
    title = title,
    outcome = outcome.name,
    outcomeText = outcomeText,
    date = formattedDate,
    sections = sections.map { section ->
        AgentHistoryDetailsSectionSnapshot(
            id = section.id,
            title = section.title,
            rows = section.rows.map { it.toSnapshot() },
        )
    },
)

private fun AgentHistoryDetails.Row.toSnapshot() = when (this) {
    is AgentHistoryDetails.Row.Text -> AgentHistoryDetailsRowSnapshot(
        id = id,
        kind = AgentHistoryDetailsRowKind.TEXT,
        icon = icon?.name.orEmpty(),
        label = label,
        value = value,
        monospace = monospace,
        reference = null,
    )

    is AgentHistoryDetails.Row.Code -> AgentHistoryDetailsRowSnapshot(
        id = id,
        kind = AgentHistoryDetailsRowKind.CODE,
        icon = icon.name,
        label = label,
        value = value,
        monospace = true,
        reference = null,
    )

    is AgentHistoryDetails.Row.Reference -> {
        val name = item?.title?.text
        val accountId = accountId
        val deepLink = if (item != null && accountId != null) {
            RouteDescriptor.VaultCipherView(itemId = cipherId, accountId = accountId)
                .toDeepLink()
        } else {
            null
        }
        AgentHistoryDetailsRowSnapshot(
            id = id,
            kind = AgentHistoryDetailsRowKind.REFERENCE,
            icon = "",
            label = label,
            value = name.orEmpty(),
            monospace = false,
            reference = VaultReferenceSnapshot(
                title = name?.takeIf { it.isNotBlank() } ?: label,
                text = label.takeIf { !name.isNullOrBlank() },
                unavailable = item == null,
                clickActionId = deepLink,
            ),
        )
    }
}
