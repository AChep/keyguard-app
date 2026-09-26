package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.browseragent.BrowserAutofillAgentStatusService
import com.artemchep.keyguard.common.usecase.GetBrowserAutofillAgentStatus
import kotlinx.coroutines.flow.distinctUntilChanged

class GetBrowserAutofillAgentStatusImpl(
    browserAutofillAgentStatusService: BrowserAutofillAgentStatusService,
) : GetBrowserAutofillAgentStatus {
    private val sharedFlow = browserAutofillAgentStatusService.getStatus()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
