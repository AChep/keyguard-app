package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.usecase.GetAgentApprovalTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetBrowserExtensionApprovalTimeout
import com.artemchep.keyguard.common.usecase.PutBrowserExtensionApprovalTimeout
import org.koin.core.scope.Scope

/** How long a browser extension approval prompt waits for the user. */
fun settingBrowserExtensionApprovalTimeoutProvider(
    koinScope: Scope,
): SettingComponent = browserExtensionSettingProvider {
    settingAgentApprovalTimeoutProvider(
        approvalTimeout = koinScope.get<GetBrowserExtensionApprovalTimeout>()(),
        approvalTimeoutVariants = koinScope.get<GetAgentApprovalTimeoutVariants>()(),
        putApprovalTimeout = koinScope.get<PutBrowserExtensionApprovalTimeout>(),
        windowCoroutineScope = koinScope.get(),
        context = koinScope.get(),
        platformClasses = browserExtensionPlatformClasses,
        searchTokens = browserExtensionSearchTokens + listOf("approval", "timeout", "expire"),
        actionIdPrefix = "settings.browserExtensionApprovalTimeout",
    )
}
