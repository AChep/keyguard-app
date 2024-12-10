package com.artemchep.keyguard.feature.localization

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

sealed interface TextHolder {
    @JvmInline
    value class Value(
        val data: String,
    ) : TextHolder

    @JvmInline
    value class Res(
        val data: StringResource,
    ) : TextHolder
}

@Suppress("NOTHING_TO_INLINE")
inline fun StringResource.wrap() = TextHolder.Res(this)

@Composable
fun textResource(text: TextHolder): String = when (text) {
    is TextHolder.Value -> text.data
    is TextHolder.Res -> stringResource(text.data)
}
