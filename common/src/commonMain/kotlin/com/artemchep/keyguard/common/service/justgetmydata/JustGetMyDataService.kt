package com.artemchep.keyguard.common.service.justgetmydata

import com.artemchep.keyguard.common.io.IO

interface JustGetMyDataService {
    fun get(): IO<List<JustGetMyDataServiceInfo>>
}
