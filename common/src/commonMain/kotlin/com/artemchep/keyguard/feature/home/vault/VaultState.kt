package com.artemchep.keyguard.feature.home.vault

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.VaultCipherItem

@Immutable
@optics
data class VaultState(
    val master: Master = Master(),
    val detail: Detail? = null,
) {
    companion object;

    data class Master(
        /**
         * Current revision of the items; each revision you should scroll to
         * the top of the list.
         */
        val itemsRevision: Int = 0,
        val items: List<VaultItem2> = emptyList(),
        val onGoClick: (() -> Unit)? = null,
    )

    data class Detail(
        val item: VaultCipherItem,
        /**
         * Called to unselect this item, closing the
         * detail pane.
         */
        val close: (() -> Unit)? = null,
    )
}
