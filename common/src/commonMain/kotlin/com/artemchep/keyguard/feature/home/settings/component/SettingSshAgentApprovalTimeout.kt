package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.usecase.GetAgentApprovalTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalTimeout
import com.artemchep.keyguard.common.usecase.PutSshAgentApprovalTimeout
import org.koin.core.scope.Scope

/** How long an SSH agent approval prompt waits for the user. */
fun settingSshAgentApprovalTimeoutProvider(
    koinScope: Scope,
): SettingComponent = platformSettingProvider(agentApprovalTimeoutPlatformClasses) {
    settingAgentApprovalTimeoutProvider(
        approvalTimeout = koinScope.get<GetSshAgentApprovalTimeout>()(),
        approvalTimeoutVariants = koinScope.get<GetAgentApprovalTimeoutVariants>()(),
        putApprovalTimeout = koinScope.get<PutSshAgentApprovalTimeout>(),
        windowCoroutineScope = koinScope.get(),
        context = koinScope.get(),
        platformClasses = agentApprovalTimeoutPlatformClasses,
        searchTokens = listOf("ssh", "git", "agent", "approval", "authorization", "sign", "timeout", "expire"),
        actionIdPrefix = "settings.sshAgentApprovalTimeout",
    )
}
