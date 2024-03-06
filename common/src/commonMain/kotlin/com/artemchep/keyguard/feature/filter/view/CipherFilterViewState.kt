package com.artemchep.keyguard.feature.filter.view

import androidx.compose.runtime.Immutable
import arrow.core.Either
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.ui.ContextItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class CipherFilterViewState(
    val toolbarFlow: StateFlow<Toolbar>,
    val filterFlow: Flow<Filter>,
    val content: Either<Throwable, Content>,
    val onClose: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val model: DCipherFilter?,
    ) {
        companion object
    }

    @Immutable
    data class Toolbar(
        val model: DCipherFilter?,
        val actions: ImmutableList<ContextItem>,
    )

    @Immutable
    data class Filter(
        val items: ImmutableList<FilterItem> = persistentListOf(),
    )
}