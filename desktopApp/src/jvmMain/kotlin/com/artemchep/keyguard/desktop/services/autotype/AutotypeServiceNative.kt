package com.artemchep.keyguard.desktop.services.autotype

import com.artemchep.autotype.autoType
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.autotype.AutotypeService

class AutotypeServiceNative : AutotypeService {

    override fun type(
        payload: String,
    ) = ioEffect {
        autoType(payload)
    }
}
