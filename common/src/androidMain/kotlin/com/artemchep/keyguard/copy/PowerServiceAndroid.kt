package com.artemchep.keyguard.copy

import android.app.Application
import com.artemchep.keyguard.android.util.screenFlow
import com.artemchep.keyguard.common.service.power.PowerService
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PowerServiceAndroid(
    private val application: Application,
) : PowerService {
    private val sharedFlow = screenFlow(application)
        .shareIn(GlobalScope, SharingStarted.WhileSubscribed(5000L), replay = 1)

    constructor(
        directDI: DirectDI,
    ) : this(
        application = directDI.instance<Application>(),
    )

    override fun getScreenState() = sharedFlow
}
