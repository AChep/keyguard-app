package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.usecase.GetAgentApprovalTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetCliApprovalTimeout
import com.artemchep.keyguard.common.usecase.PutCliApprovalTimeout
import org.koin.core.scope.Scope

/** How long a `keyguard` CLI approval prompt waits for the user. */
fun settingCliApprovalTimeoutProvider(
    koinScope: Scope,
): SettingComponent = cliSettingProvider {
    settingAgentApprovalTimeoutProvider(
        approvalTimeout = koinScope.get<GetCliApprovalTimeout>()(),
        approvalTimeoutVariants = koinScope.get<GetAgentApprovalTimeoutVariants>()(),
        putApprovalTimeout = koinScope.get<PutCliApprovalTimeout>(),
        windowCoroutineScope = koinScope.get(),
        context = koinScope.get(),
        platformClasses = cliPlatformClasses,
        searchTokens = listOf("cli", "run", "terminal", "approval", "timeout", "expire"),
        actionIdPrefix = "settings.cliApprovalTimeout",
    )
}
