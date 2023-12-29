package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.io.IO

interface PassKeyService {
    fun get(): IO<List<PassKeyServiceInfo>>
}
