package com.artemchep.keyguard.feature.tfa.directory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import arrow.core.Either
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.justgetdata.directory.JustGetMyDataListState
import kotlinx.coroutines.flow.StateFlow

data class TwoFaServiceListState(
    val filter: StateFlow<Filter>,
    val content: Loadable<Either<Throwable, Content>>,
) {
    @Immutable
    data class Filter(
        val revision: Int,
        val query: TextFieldModel2,
    ) {
        companion object
    }

    @Immutable
    data class Content(
        val revision: Int,
        val items: List<Item>,
    ) {
        companion object
    }

    @Immutable
    sealed interface Item {
        val key: String

        @Immutable
        data class Section(
            override val key: String,
            val name: String,
        ) : Item

        @Immutable
        data class Content(
            override val key: String,
            val icon: @Composable () -> Unit,
            val shapeState: Int = ShapeState.ALL,
            val name: AnnotatedString,
            val data: TwoFaServiceInfo,
            val onClick: (() -> Unit)? = null,
        ) : Item
    }
}