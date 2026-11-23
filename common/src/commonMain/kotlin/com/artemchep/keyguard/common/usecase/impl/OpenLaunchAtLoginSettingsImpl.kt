package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginService
import com.artemchep.keyguard.common.usecase.OpenLaunchAtLoginSettings

/** A failure is published in [LaunchAtLoginService.state]. */
class OpenLaunchAtLoginSettingsImpl(
    private val service: LaunchAtLoginService,
) : OpenLaunchAtLoginSettings {
    override fun invoke(): IO<Unit> = {
        service.openSettings()
    }
}
