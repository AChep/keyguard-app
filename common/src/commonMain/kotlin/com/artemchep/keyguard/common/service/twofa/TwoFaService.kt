package com.artemchep.keyguard.common.service.twofa

import com.artemchep.keyguard.common.io.IO

interface TwoFaService {
    val version: String

    fun get(): IO<List<TwoFaServiceInfo>>
}
