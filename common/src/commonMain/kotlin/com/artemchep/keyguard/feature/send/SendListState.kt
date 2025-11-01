package com.artemchep.keyguard.feature.send

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.send.search.SendSortItem
import com.artemchep.keyguard.feature.send.search.filter.SendFilterItem
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Immutable
@optics
data class SendListState(
    val revision: Int = 0,
    val query: TextFieldModel2 = TextFieldModel2(mutableStateOf("")),
    val filters: List<SendFilterItem> = emptyList(),
    val sort: List<SendSortItem> = emptyList(),
    val saveFilters: (() -> Unit)? = null,
    val clearFilters: (() -> Unit)? = null,
    val clearSort: (() -> Unit)? = null,
    val showKeyboard: Boolean = false,
    val primaryActions: ImmutableList<ContextItem> = persistentListOf(),
    val actions: List<FlatItemAction> = emptyList(),
    val content: Content = Content.Skeleton,
    val sideEffects: SideEffects = SideEffects(),
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
            val list: List<SendItem> = emptyList(),
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

    @Immutable
    @optics
    data class SideEffects(
        val showBiometricPromptFlow: Flow<DSend> = emptyFlow(),
    ) {
        companion object
    }
}
