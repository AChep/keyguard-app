package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.usecase.GetEnvironmentsApprovalWindowVariants
import com.artemchep.keyguard.common.usecase.GetEnvironmentsMcpApprovalWindow
import com.artemchep.keyguard.common.usecase.PutEnvironmentsMcpApprovalWindow
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_approval_window_title
import org.koin.core.scope.Scope

/** How long an MCP client, or a reader of a local `.env` file, keeps an approval. */
fun settingEnvironmentsMcpApprovalWindowProvider(
    koinScope: Scope,
): SettingComponent = environmentsSettingProvider {
    settingAgentApprovalWindowProvider(
        approvalWindow = koinScope.get<GetEnvironmentsMcpApprovalWindow>()(),
        approvalWindowVariants = koinScope.get<GetEnvironmentsApprovalWindowVariants>()(),
        putApprovalWindow = koinScope.get<PutEnvironmentsMcpApprovalWindow>(),
        windowCoroutineScope = koinScope.get(),
        context = koinScope.get(),
        platformClasses = environmentsPlatformClasses,
        searchTokens = listOf("environments", "mcp", "agent", "approval", "remember"),
        title = Res.string.pref_item_approval_window_title,
        actionIdPrefix = "settings.environmentsMcpApprovalWindow",
    )
}
