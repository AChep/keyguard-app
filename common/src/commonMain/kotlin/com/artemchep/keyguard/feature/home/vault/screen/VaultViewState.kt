package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.StateFlow

@Immutable
@optics
data class VaultViewState(
    val content: Content = Content.Loading,
) {
    companion object;

    @Immutable
    @optics
    sealed interface Content {
        companion object;

        @Immutable
        @optics
        data class Cipher(
            val locked: StateFlow<Boolean>,
            val data: DSecret,
            val icon: VaultItemIcon,
            val synced: Boolean,
            val onFavourite: ((Boolean) -> Unit)?,
            val onEdit: (() -> Unit)?,
            val actions: List<FlatItemAction>,
            val primaryAction: FlatItemAction?,
            val items: List<VaultViewItem>,
        ) : Content {
            companion object;
        }

        @Immutable
        data object NotFound : Content

        @Immutable
        data object Loading : Content
    }
}
