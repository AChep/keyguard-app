package com.artemchep.keyguard.feature.generator.wordlist.view

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import arrow.core.Either
import com.artemchep.keyguard.common.model.DGeneratorWordlist
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.ui.ContextItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class WordlistViewState(
    val wordlist: StateFlow<Wordlist?>,
    val filter: StateFlow<Filter>,
    val content: Loadable<Either<Throwable, Content>>,
) {
    @Immutable
    data class Wordlist(
        val wordlist: DGeneratorWordlist,
        val actions: ImmutableList<ContextItem>,
    )

    @Immutable
    data class Filter(
        val revision: Int,
        val query: TextFieldModel2,
    )

    @Immutable
    data class Content(
        val revision: Int,
        val items: List<Item>,
    )

    @Immutable
    data class Item(
        val key: String,
        val name: AnnotatedString,
        val shapeState: Int = ShapeState.ALL,
        val onClick: (() -> Unit)? = null,
    ) : GroupableShapeItem<Item> {
        override fun withShape(shape: Int) = copy(shapeState = shape)
    }
}
