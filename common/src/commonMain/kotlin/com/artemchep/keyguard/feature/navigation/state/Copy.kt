package com.artemchep.keyguard.feature.navigation.state

import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.CopyText

fun RememberStateFlowScope.copy(
    clipboardService: ClipboardService,
) = CopyText(
    clipboardService = clipboardService,
    onMessage = ::message,
)
