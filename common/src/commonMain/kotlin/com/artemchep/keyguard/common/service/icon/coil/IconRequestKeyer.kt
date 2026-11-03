package com.artemchep.keyguard.common.service.icon.coil

import coil3.key.Keyer
import coil3.request.Options
import com.artemchep.keyguard.feature.favicon.IconRequest
import com.artemchep.keyguard.feature.favicon.toIconKey

class IconRequestKeyer : Keyer<IconRequest> {
    override fun key(
        data: IconRequest,
        options: Options,
    ): String = data.toIconKey()
}
