package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.model.VaultPasswordHistoryItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import kotlinx.collections.immutable.ImmutableList

@Immutable
@optics
data class VaultViewPasswordHistoryState(
    val content: Content = Content.Loading,
) {
    companion object;

    @Immutable
    sealed interface Content {
        companion object;

        @Immutable
        data class Cipher(
            val selection: Selection? = null,
            val data: DSecret,
            val items: ImmutableList<VaultPasswordHistoryItem>,
            val actions: ImmutableList<FlatItemAction>,
        ) : Content {
            companion object;
        }

        @Immutable
        data object NotFound : Content

        @Immutable
        data object Loading : Content
    }
}
