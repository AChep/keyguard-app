package com.artemchep.keyguard.wear.feature.send

import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.feature.send.search.SendSortItem
import com.artemchep.keyguard.feature.send.search.filter.SendFilterItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@optics
data class WearSendListState(
    val revision: Int = 0,
    val filters: ImmutableList<SendFilterItem> = persistentListOf(),
    val sort: ImmutableList<SendSortItem> = persistentListOf(),
    val saveFilters: (() -> Unit)? = null,
    val clearFilters: (() -> Unit)? = null,
    val clearSort: (() -> Unit)? = null,
    val content: Content = Content.Skeleton,
    val sideEffects: SideEffects = SideEffects(),
) {
    companion object;

    sealed interface Content {
        companion object;

        data object Skeleton : Content

        @optics
        data class Items(
            val revision: Revision = Revision(),
            val list: ImmutableList<SendItem> = persistentListOf(),
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
        val showBiometricPromptFlow: Flow<DSend> = emptyFlow(),
    ) {
        companion object
    }
}
