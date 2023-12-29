package com.artemchep.keyguard.feature.localization

import com.artemchep.keyguard.platform.LeContext
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.ResourceFormatted
import dev.icerock.moko.resources.desc.StringDesc

actual fun textResource(res: StringResource, context: LeContext): String =
    StringDesc.Resource(res).localized()

actual fun textResource(
    res: StringResource,
    context: LeContext,
    vararg args: Any,
): String = StringDesc.ResourceFormatted(res, *args).localized()
