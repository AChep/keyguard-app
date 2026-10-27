package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindowVariants
import com.artemchep.keyguard.common.usecase.PutGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import org.koin.core.scope.Scope

fun settingGpgAgentApprovalWindowProvider(
    koinScope: Scope,
) = settingGpgAgentApprovalWindowProvider(
    getGpgAgentApprovalWindow = koinScope.get(),
    getGpgAgentApprovalWindowVariants = koinScope.get(),
    putGpgAgentApprovalWindow = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
    context = koinScope.get(),
)

fun settingGpgAgentApprovalWindowProvider(
    getGpgAgentApprovalWindow: GetGpgAgentApprovalWindow,
    getGpgAgentApprovalWindowVariants: GetGpgAgentApprovalWindowVariants,
    putGpgAgentApprovalWindow: PutGpgAgentApprovalWindow,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = settingAgentApprovalWindowProvider(
    approvalWindow = getGpgAgentApprovalWindow(),
    approvalWindowVariants = getGpgAgentApprovalWindowVariants(),
    putApprovalWindow = putGpgAgentApprovalWindow,
    windowCoroutineScope = windowCoroutineScope,
    context = context,
    platformClasses = listOf(
        Platform.Mobile.Android::class,
        Platform.Desktop.Linux::class,
        Platform.Desktop.MacOS::class,
        Platform.Desktop.Windows::class,
        Platform.Desktop.Other::class,
    ),
    searchTokens = listOf(
        "gpg",
        "gnupg",
        "agent",
        "approval",
        "sign",
        "remember",
    ),
)
