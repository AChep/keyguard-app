package com.artemchep.keyguard.feature.credentialexchange

import com.artemchep.keyguard.common.service.credentialexchange.CxfSkips
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.PluralStringResource

/**
 * Opens a closed note, closes an open one. Shared by both review screens, whose
 * producers hold the open ids so a lazy row scrolling out cannot forget them.
 */
internal fun MutableStateFlow<Set<String>>.toggleNote(id: String) {
    update { ids ->
        if (id in ids) ids - id else ids + id
    }
}

/**
 * Turns a skip tally into the review screen's warning rows: one row per reason
 * that actually fired, in enum-declaration order.
 *
 * Shared by both directions because the two tallies differ only in their reason
 * enum; [labelRes] supplies the label for each reason, and keeping it a
 * parameter is what keeps each direction's `when` exhaustive.
 *
 * A row is expandable exactly when the tally could attribute something to it.
 * Reasons that fire on unreadable input — a non-object account entry, an item
 * whose shell failed to decode — attribute nothing and so get no toggle, which
 * renders them precisely as they were before notes could expand.
 */
internal fun <R : Enum<R>> CxfSkips<R>.toSkippedNotes(
    expandedIds: Set<String>,
    onToggle: (String) -> Unit,
    labelRes: (R) -> PluralStringResource,
): List<CredentialExchangeSkippedNote> = counted.map { (reason, count) ->
    val id = reason.name
    val titles = titlesOf(reason)
        .map { (text, titleCount) ->
            CredentialExchangeSkippedNote.Title(
                text = text,
                count = titleCount,
            )
        }
    CredentialExchangeSkippedNote(
        id = id,
        count = count,
        res = labelRes(reason),
        titles = titles,
        expanded = id in expandedIds,
        onToggle = if (titles.isEmpty()) {
            null
        } else {
            // lambda
            {
                onToggle(id)
            }
        },
    )
}
