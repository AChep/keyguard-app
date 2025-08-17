package com.artemchep.keyguard.feature.localizationcontributors.directory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import arrow.core.Either
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo
import com.artemchep.keyguard.common.service.localizationcontributors.LocalizationContributor
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import kotlinx.coroutines.flow.StateFlow

data class LocalizationContributorsListState(
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
    data class Item(
        val key: String,
        val icon: @Composable () -> Unit,
        val name: AnnotatedString,
        val score: Int,
        val index: Int,
        val shapeState: Int = ShapeState.ALL,
        val data: LocalizationContributor,
        val onClick: (() -> Unit)? = null,
    ) : GroupableShapeItem<Item> {
        override fun withShape(shape: Int) = copy(shapeState = shape)
    }
}