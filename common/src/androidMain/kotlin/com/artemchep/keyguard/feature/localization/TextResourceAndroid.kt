package com.artemchep.keyguard.feature.localization

import android.content.Context
import com.artemchep.keyguard.platform.LeContext

suspend fun textResource(text: TextHolder, context: Context): String = when (text) {
    is TextHolder.Value -> text.data
    is TextHolder.Res -> textResource(text.data, LeContext(context))
}
