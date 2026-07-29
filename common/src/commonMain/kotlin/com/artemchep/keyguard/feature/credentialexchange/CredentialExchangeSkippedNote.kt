package com.artemchep.keyguard.feature.credentialexchange

import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.PluralStringResource

/**
 * One warning row on a credential-exchange review screen: how many things were
 * lost, the plural label that says what they were, and — when they could be
 * attributed — the items behind them.
 */
@Immutable
data class CredentialExchangeSkippedNote(
    /**
     * Stable across emissions, so the lazy list can key the row and the screen
     * can remember which notes are open. The skip reason's enum name.
     */
    val id: String,
    val count: Int,
    val res: PluralStringResource,
    /**
     * The items behind this note, ordered and already grouped so one item that
     * lost three attachments appears once.
     *
     * May account for fewer than [count]: several reasons fire on input that
     * carries no readable title at all. [remainingCount] is that difference, and
     * the row renders it rather than let the list imply it is the whole story.
     */
    val titles: List<Title> = emptyList(),
    val expanded: Boolean = false,
    /**
     * Opens and closes [titles]. `null` when there is nothing to show, which is
     * the only gate on the affordance — such a row renders exactly as it did
     * before notes could expand.
     */
    val onToggle: (() -> Unit)? = null,
) {
    /** How much of [count] the listed [titles] leave unaccounted for. */
    val remainingCount: Int get() = (count - titles.sumOf { it.count }).coerceAtLeast(0)

    /**
     * One item behind a note, and how many of the note's count it accounts for.
     * [count] is above one when the same item lost several things for the same
     * reason — three attachments on one login.
     */
    @Immutable
    data class Title(
        val text: String,
        val count: Int,
    )
}
