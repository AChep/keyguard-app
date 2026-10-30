package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.usecase.GetAgentApprovalTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetEnvironmentsMcpApprovalTimeout
import com.artemchep.keyguard.common.usecase.PutEnvironmentsMcpApprovalTimeout
import org.koin.core.scope.Scope

/** How long an MCP or local `.env` file approval prompt waits for the user. */
fun settingEnvironmentsMcpApprovalTimeoutProvider(
    koinScope: Scope,
): SettingComponent = environmentsSettingProvider {
    settingAgentApprovalTimeoutProvider(
        approvalTimeout = koinScope.get<GetEnvironmentsMcpApprovalTimeout>()(),
        approvalTimeoutVariants = koinScope.get<GetAgentApprovalTimeoutVariants>()(),
        putApprovalTimeout = koinScope.get<PutEnvironmentsMcpApprovalTimeout>(),
        windowCoroutineScope = koinScope.get(),
        context = koinScope.get(),
        platformClasses = environmentsPlatformClasses,
        searchTokens = listOf("environments", "mcp", "agent", "approval", "timeout", "expire"),
        actionIdPrefix = "settings.environmentsMcpApprovalTimeout",
    )
}
