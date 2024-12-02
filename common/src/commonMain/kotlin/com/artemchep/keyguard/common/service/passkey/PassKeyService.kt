package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.io.IO

interface PassKeyService {
    val version: String

    fun get(): IO<List<PassKeyServiceInfo>>
}
