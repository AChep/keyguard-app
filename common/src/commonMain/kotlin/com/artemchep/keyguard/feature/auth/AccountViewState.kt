package com.artemchep.keyguard.feature.auth

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.ContextItem

@Immutable
@optics
data class AccountViewState(
    val content: Content = Content.Skeleton,
) {
    companion object;

    @optics
    sealed interface Content {
        companion object;

        data object Skeleton : Content

        data object NotFound : Content

        @optics
        data class Data(
            val data: DAccount,
            val items: List<VaultViewItem>,
            val actions: List<ContextItem>,
            val primaryAction: PrimaryAction? = null,
            val onOpenWebVault: (() -> Unit)? = null,
            val onOpenLocalVault: (() -> Unit)? = null,
        ) : Content {
            companion object;

            data class PrimaryAction(
                val text: String = "",
                val icon: ImageVector? = null,
                val onClick: (() -> Unit)? = null,
            )
        }
    }
}
