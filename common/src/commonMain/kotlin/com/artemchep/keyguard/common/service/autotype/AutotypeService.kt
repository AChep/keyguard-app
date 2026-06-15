package com.artemchep.keyguard.common.service.autotype

import com.artemchep.keyguard.common.io.IO

interface AutotypeService {
    fun type(
        payload: String,
    ): IO<Unit>
}
