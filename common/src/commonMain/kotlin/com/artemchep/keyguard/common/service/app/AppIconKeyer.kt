package com.artemchep.keyguard.common.service.app

import coil3.key.Keyer
import com.artemchep.keyguard.feature.favicon.AppIconUrl

class AppIconKeyer : Keyer<AppIconUrl> {
    override fun key(
        data: AppIconUrl,
        options: coil3.request.Options,
    ): String? = "androidapp://${data.packageName}"
}
