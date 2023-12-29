package com.artemchep.keyguard.common.service.gpmprivapps.impl

import arrow.core.partially1
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppsService
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.readFromResourcesAsText
import com.artemchep.keyguard.res.Res
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PrivilegedAppsServiceImpl(
    private val textService: TextService,
) : PrivilegedAppsService {
    private val jsonIo = ::loadJustDeleteMeRawData
        .partially1(textService)

    constructor(
        directDI: DirectDI,
    ) : this(
        textService = directDI.instance(),
    )

    override fun get() = jsonIo
}

private suspend fun loadJustDeleteMeRawData(
    textService: TextService,
) = textService.readFromResourcesAsText(Res.files.gpm_passkeys_privileged_apps)
