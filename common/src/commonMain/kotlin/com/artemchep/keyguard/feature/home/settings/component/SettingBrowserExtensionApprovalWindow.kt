package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.usecase.GetBrowserExtensionApprovalWindow
import com.artemchep.keyguard.common.usecase.GetEnvironmentsApprovalWindowVariants
import com.artemchep.keyguard.common.usecase.PutBrowserExtensionApprovalWindow
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_approval_window_title
import org.koin.core.scope.Scope

/** How long a paired browser keeps an approval to fill an item on a site. */
fun settingBrowserExtensionApprovalWindowProvider(
    koinScope: Scope,
): SettingComponent = browserExtensionSettingProvider {
    settingAgentApprovalWindowProvider(
        approvalWindow = koinScope.get<GetBrowserExtensionApprovalWindow>()(),
        approvalWindowVariants = koinScope.get<GetEnvironmentsApprovalWindowVariants>()(),
        putApprovalWindow = koinScope.get<PutBrowserExtensionApprovalWindow>(),
        windowCoroutineScope = koinScope.get(),
        context = koinScope.get(),
        platformClasses = browserExtensionPlatformClasses,
        searchTokens = browserExtensionSearchTokens + listOf("approval", "remember"),
        title = Res.string.pref_item_approval_window_title,
        actionIdPrefix = "settings.browserExtensionApprovalWindow",
    )
}
