package com.artemchep.keyguard.feature.localization

import android.content.Context
import com.artemchep.keyguard.platform.LeContext
import dev.icerock.moko.resources.PluralsResource
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.desc.PluralFormatted
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.ResourceFormatted
import dev.icerock.moko.resources.desc.StringDesc
import dev.icerock.moko.resources.desc.desc

fun textResource(text: TextHolder, context: Context): String = when (text) {
    is TextHolder.Value -> text.data.desc()
    is TextHolder.Res -> text.data.desc()
}.toString(context)

actual fun textResource(res: StringResource, context: LeContext): String =
    StringDesc.Resource(res).toString(context.context)

actual fun textResource(
    res: StringResource,
    context: LeContext,
    vararg args: Any,
): String =
    StringDesc.ResourceFormatted(res, *args).toString(context.context)

actual fun textResource(
    res: PluralsResource,
    context: LeContext,
    quantity: Int,
    vararg args: Any,
): String =
    StringDesc.PluralFormatted(res, quantity, *args).toString(context.context)
