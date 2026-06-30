package com.artemchep.keyguard.feature.home.settings.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel

data class SearchSettingsState(
    val revision: Int = 0,
    val query: TextFieldModel = TextFieldModel(text = ""),
    val items: List<Item> = emptyList(),
) {
    sealed interface Item {
        val key: String
        val contentType: String

        data class Settings(
            override val key: String,
            val score: Float,
            val shapeState: Int = ShapeState.ALL,
            val content: @Composable () -> Unit,
        ) : Item, GroupableShapeItem<Settings> {
            override val contentType: String get() = "settings_search_item"

            override fun withShape(shape: Int): Settings = copy(shapeState = shape)
        }
    }
}
