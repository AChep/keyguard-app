package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.usecase.GetAgentApprovalTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalTimeout
import com.artemchep.keyguard.common.usecase.PutGpgAgentApprovalTimeout
import org.koin.core.scope.Scope

/** How long a GPG agent approval prompt waits for the user. */
fun settingGpgAgentApprovalTimeoutProvider(
    koinScope: Scope,
): SettingComponent = platformSettingProvider(agentApprovalTimeoutPlatformClasses) {
    settingAgentApprovalTimeoutProvider(
        approvalTimeout = koinScope.get<GetGpgAgentApprovalTimeout>()(),
        approvalTimeoutVariants = koinScope.get<GetAgentApprovalTimeoutVariants>()(),
        putApprovalTimeout = koinScope.get<PutGpgAgentApprovalTimeout>(),
        windowCoroutineScope = koinScope.get(),
        context = koinScope.get(),
        platformClasses = agentApprovalTimeoutPlatformClasses,
        searchTokens = listOf("gpg", "gnupg", "agent", "approval", "sign", "timeout", "expire"),
        actionIdPrefix = "settings.gpgAgentApprovalTimeout",
    )
}
