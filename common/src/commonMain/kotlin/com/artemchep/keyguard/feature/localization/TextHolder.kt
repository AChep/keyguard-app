package com.artemchep.keyguard.feature.localization

import kotlin.jvm.JvmInline
import kotlin.jvm.JvmName

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Immutable
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

@JvmName("textResourceOrNull")
@Composable
fun textResource(text: TextHolder?) = when (text) {
    null -> null
    else -> textResource(text)
}
