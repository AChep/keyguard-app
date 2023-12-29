package com.artemchep.keyguard.common.service.gpmprivapps

import com.artemchep.keyguard.common.io.IO

interface PrivilegedAppsService {
    fun get(): IO<String>
}
