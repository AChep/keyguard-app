package com.artemchep.keyguard.common.service.justdeleteme

import com.artemchep.keyguard.common.io.IO

interface JustDeleteMeService {
    fun get(): IO<List<JustDeleteMeServiceInfo>>
}
