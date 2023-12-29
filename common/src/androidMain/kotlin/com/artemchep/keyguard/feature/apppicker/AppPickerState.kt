package com.artemchep.keyguard.feature.apppicker

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import arrow.core.Either
import arrow.optics.optics
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import kotlinx.coroutines.flow.StateFlow

data class AppPickerState(
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
    @optics
    data class Content(
        val revision: Int,
        val items: List<Item>,
    ) {
        companion object
    }

    @Immutable
    @optics
    data class Item(
        val key: String,
        val icon: AppIconUrl,
        val name: AnnotatedString,
        val text: String,
        val system: Boolean,
        val onClick: (() -> Unit)? = null,
    ) {
        companion object
    }
}
