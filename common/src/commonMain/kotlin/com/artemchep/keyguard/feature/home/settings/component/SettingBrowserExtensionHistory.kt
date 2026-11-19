package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import org.koin.core.scope.Scope

/** The request log of the paired browsers. */
fun settingBrowserExtensionHistoryProvider(
    koinScope: Scope,
) = browserExtensionSettingProvider {
    settingCliAgentHistoryProvider(
        client = CliAgentUsageHistoryClient.BROWSER,
        getCliAgentUsageHistoryCount = koinScope.get(),
        numberFormatter = koinScope.get(),
    )
}
