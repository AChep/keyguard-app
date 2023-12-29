package com.artemchep.keyguard.feature.localization

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource

sealed interface TextHolder {
    data class Value(
        val data: String,
    ) : TextHolder

    data class Res(
        val data: StringResource,
    ) : TextHolder
}

@Composable
fun textResource(text: TextHolder): String = when (text) {
    is TextHolder.Value -> text.data
    is TextHolder.Res -> stringResource(text.data)
}
