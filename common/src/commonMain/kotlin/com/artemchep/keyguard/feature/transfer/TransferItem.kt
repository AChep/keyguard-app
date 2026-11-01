package com.artemchep.keyguard.feature.transfer

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort

/**
 * One vault item on an import or export review screen, with the kinds of
 * credential it carries.
 *
 * Shared by both directions on purpose: the export and import review screens answer
 * the same question — "which items are about to move, and what is in them?" — so they
 * render the same rows. The projections differ (export reads the wire document, import
 * reads the vault-write requests it is about to perform), but the model does not.
 */
@Immutable
data class TransferItem(
    val title: String,
    val shapeState: Int,
    val credentials: List<Kind>,
) {
    /**
     * Declaration order is badge order, so an item reads the same way on both
     * screens.
     */
    enum class Kind {
        Passkey,
        Password,
        Totp,
        Card,
        Identity,
        Note,
        Fields,
        SshKey,
        GpgKey,
        Environment,
    }
}

/**
 * Orders import and export review rows with the same locale-aware comparison
 * as the vault list.
 *
 * Kotlin's list sort is stable, so titles that the platform collator considers
 * equal keep their source order.
 */
internal fun <T> Iterable<T>.sortedTransferItemsBy(
    titleOf: (T) -> String,
): List<T> = sortedWith { a, b ->
    AlphabeticalSort.compareStr(
        titleOf(a),
        titleOf(b),
    )
}
