package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.usecase.GetCliApprovalWindow
import com.artemchep.keyguard.common.usecase.GetEnvironmentsApprovalWindowVariants
import com.artemchep.keyguard.common.usecase.PutCliApprovalWindow
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_approval_window_title
import org.koin.core.scope.Scope

/** How long a terminal tab keeps an approval of the CLI. */
fun settingCliApprovalWindowProvider(
    koinScope: Scope,
): SettingComponent = cliSettingProvider {
    settingAgentApprovalWindowProvider(
        approvalWindow = koinScope.get<GetCliApprovalWindow>()(),
        approvalWindowVariants = koinScope.get<GetEnvironmentsApprovalWindowVariants>()(),
        putApprovalWindow = koinScope.get<PutCliApprovalWindow>(),
        windowCoroutineScope = koinScope.get(),
        context = koinScope.get(),
        platformClasses = cliPlatformClasses,
        searchTokens = listOf("cli", "run", "terminal", "approval", "remember"),
        title = Res.string.pref_item_approval_window_title,
        actionIdPrefix = "settings.cliApprovalWindow",
    )
}
