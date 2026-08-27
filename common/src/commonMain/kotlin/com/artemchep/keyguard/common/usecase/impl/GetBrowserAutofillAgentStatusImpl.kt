package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.browseragent.BrowserAutofillAgentStatusService
import com.artemchep.keyguard.common.usecase.GetBrowserAutofillAgentStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetBrowserAutofillAgentStatusImpl(
    browserAutofillAgentStatusService: BrowserAutofillAgentStatusService,
) : GetBrowserAutofillAgentStatus {
    private val sharedFlow = browserAutofillAgentStatusService.getStatus()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        browserAutofillAgentStatusService = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
