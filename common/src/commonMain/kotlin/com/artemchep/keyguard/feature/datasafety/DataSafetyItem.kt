package com.artemchep.keyguard.feature.datasafety

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
sealed interface DataSafetyItem {
    val key: String

    data class LargeSection(
        override val key: String,
        val text: String,
    ) : DataSafetyItem

    data class Section(
        override val key: String,
        val text: String,
    ) : DataSafetyItem

    data class Text(
        override val key: String,
        val text: String,
        val secondary: Boolean = false,
    ) : DataSafetyItem

    data class Row(
        override val key: String,
        val title: String,
        val value: String,
        val secondary: Boolean = false,
    ) : DataSafetyItem

    data class Spacer(
        override val key: String,
        val height: Dp,
    ) : DataSafetyItem

    data class Divider(
        override val key: String,
        val verticalPadding: Dp,
        val horizontalPadding: Dp = 0.dp,
    ) : DataSafetyItem

    data class LearnMore(
        override val key: String,
        val url: String,
    ) : DataSafetyItem
}
