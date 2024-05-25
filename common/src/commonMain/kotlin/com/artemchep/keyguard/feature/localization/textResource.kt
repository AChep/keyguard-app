package com.artemchep.keyguard.feature.localization

import com.artemchep.keyguard.platform.LeContext
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

suspend fun textResource(
    res: StringResource,
    context: LeContext,
): String = getString(res)

suspend fun textResource(
    res: StringResource,
    context: LeContext,
    vararg args: Any,
): String = getString(res, *args)

suspend fun textResource(
    res: PluralStringResource,
    context: LeContext,
    quantity: Int,
    vararg args: Any,
): String = getPluralString(res, quantity, *args)
