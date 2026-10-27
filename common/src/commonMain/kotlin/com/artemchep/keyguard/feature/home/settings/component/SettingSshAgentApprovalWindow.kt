package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindowVariants
import com.artemchep.keyguard.common.usecase.PutSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasWatch
import kotlinx.coroutines.flow.flowOf
import org.koin.core.scope.Scope

fun settingSshAgentApprovalWindowProvider(
    koinScope: Scope,
) = settingSshAgentApprovalWindowProvider(
    getSshAgentApprovalWindow = koinScope.get(),
    getSshAgentApprovalWindowVariants = koinScope.get(),
    putSshAgentApprovalWindow = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
    context = koinScope.get(),
)

fun settingSshAgentApprovalWindowProvider(
    getSshAgentApprovalWindow: GetSshAgentApprovalWindow,
    getSshAgentApprovalWindowVariants: GetSshAgentApprovalWindowVariants,
    putSshAgentApprovalWindow: PutSshAgentApprovalWindow,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent {
    // I can not imagine many people running the
    // SSH agent on their watch.
    if (CurrentPlatform.hasWatch()) return flowOf(null)

    return settingAgentApprovalWindowProvider(
        approvalWindow = getSshAgentApprovalWindow(),
        approvalWindowVariants = getSshAgentApprovalWindowVariants(),
        putApprovalWindow = putSshAgentApprovalWindow,
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
            "ssh",
            "git",
            "agent",
            "approval",
            "authorization",
            "sign",
            "remember",
            "timeout",
        ),
        actionIdPrefix = "settings.sshAgentApprovalWindow",
    )
}
