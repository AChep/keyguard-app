package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginService
import com.artemchep.keyguard.common.usecase.PutLaunchAtLogin

/** A failure is published in [LaunchAtLoginService.state]. */
class PutLaunchAtLoginImpl(
    private val service: LaunchAtLoginService,
) : PutLaunchAtLogin {
    override fun invoke(enabled: Boolean): IO<Unit> = {
        service.setEnabled(enabled)
    }
}
