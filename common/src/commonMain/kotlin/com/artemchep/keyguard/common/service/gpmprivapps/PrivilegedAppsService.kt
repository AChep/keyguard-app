package com.artemchep.keyguard.common.service.gpmprivapps

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DPrivilegedApp

interface PrivilegedAppsService {
    fun get(): IO<String>

    fun stringify(
        privilegedApps: List<DPrivilegedApp>,
    ): IO<String>
}
