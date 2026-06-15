package com.artemchep.keyguard.wear.feature.vault

import androidx.compose.runtime.mutableStateOf
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.model.SortItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.VaultListState
import com.artemchep.keyguard.feature.home.vault.screen.VaultListState.SideEffects
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierApplyResult
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlighting
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@optics
data class WearVaultListState(
    val revision: Int = 0,
    val filters: ImmutableList<FilterItem> = persistentListOf(),
    val sort: ImmutableList<SortItem> = persistentListOf(),
    val saveFilters: (() -> Unit)? = null,
    val clearFilters: (() -> Unit)? = null,
    val clearSort: (() -> Unit)? = null,
    val selectCipher: ((DSecret) -> Unit)? = null,
    val content: Content = Content.Skeleton,
    val sideEffects: VaultListState.SideEffects = VaultListState.SideEffects(),
) {
    companion object;

    sealed interface Content {
        companion object;

        data object Skeleton : Content

        @optics
        data class AddAccount(
            val onAddAccount: ((AccountType) -> Unit)? = null,
        ) : Content {
            companion object
        }

        @optics
        data class Items(
            val revision: Revision = Revision(),
            val selection: Selection? = null,
            val list: ImmutableList<VaultItem2> = persistentListOf(),
            val count: Int = 0,
            val onSelected: (String?) -> Unit = { },
            val onGoClick: (() -> Unit)? = null,
        ) : Content {
            companion object;

            @optics
            data class Revision(
                /**
                 * Current revision of the items; each revision you should scroll to
                 * the top of the list.
                 */
                val id: Int = 0,
                val firstVisibleItemIndex: Mutable<Int> = MutableImpl(0),
                val firstVisibleItemScrollOffset: Mutable<Int> = MutableImpl(0),
                val onScroll: (Int, Int) -> Unit = { _, _ -> },
            ) : Content {
                companion object;

                interface Mutable<T> {
                    val value: T
                }

                data class MutableImpl<T>(
                    override val value: T,
                ) : Mutable<T>
            }
        }
    }

    @optics
    data class SideEffects(
        val showBiometricPromptFlow: Flow<DSecret> = emptyFlow(),
    ) {
        companion object
    }
}
