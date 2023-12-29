package com.artemchep.keyguard.feature.home.settings.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2

data class SearchSettingsState(
    val revision: Int = 0,
    val query: TextFieldModel2 = TextFieldModel2(mutableStateOf("")),
    val items: List<Item> = emptyList(),
) {
    sealed interface Item {
        val key: String

        data class Settings(
            override val key: String,
            val score: Float,
            val content: @Composable () -> Unit,
        ) : Item
    }
}
