package com.artemchep.keyguard.feature.send.view

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction

@Immutable
@optics
data class SendViewState(
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
            val data: DSend,
            val icon: VaultItemIcon,
            val synced: Boolean,
            val onCopy: (() -> Unit)?,
            val onShare: (() -> Unit)?,
            val actions: List<ContextItem>,
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
