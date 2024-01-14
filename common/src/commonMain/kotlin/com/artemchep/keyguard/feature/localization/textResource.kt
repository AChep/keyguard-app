package com.artemchep.keyguard.feature.localization

import com.artemchep.keyguard.platform.LeContext
import dev.icerock.moko.resources.PluralsResource
import dev.icerock.moko.resources.StringResource

expect fun textResource(
    res: StringResource,
    context: LeContext,
): String

expect fun textResource(
    res: StringResource,
    context: LeContext,
    vararg args: Any,
): String

expect fun textResource(
    res: PluralsResource,
    context: LeContext,
    quantity: Int,
    vararg args: Any,
): String
