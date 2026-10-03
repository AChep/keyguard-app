package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetWebDavTransactions
import com.artemchep.keyguard.common.usecase.PutWebDavTransactions
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingWebDavTransactionsProvider(
    koinScope: Scope,
) = settingWebDavTransactionsProvider(
    getWebDavTransactions = koinScope.get(),
    putWebDavTransactions = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

fun settingWebDavTransactionsProvider(
    getWebDavTransactions: GetWebDavTransactions,
    putWebDavTransactions: PutWebDavTransactions,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getWebDavTransactions().map { webDavTransactions ->
    val onCheckedChange = { shouldUseTransactions: Boolean ->
        putWebDavTransactions(shouldUseTransactions)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf(
                "webdav",
                "transactions",
                "atomic",
                "move",
                "keepass",
            ),
        ),
    ) {
        SettingWebDavTransactions(
            checked = webDavTransactions,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingWebDavTransactions(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.CloudSync,
        title = stringResource(Res.string.pref_item_webdav_transactions_title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
