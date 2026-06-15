package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingVaultClearProvider(
    directDI: DirectDI,
) = settingVaultClearProvider(
    clearData = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingVaultClearProvider(
    clearData: ClearData,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = run {
    if (!CurrentPlatform.hasWatch()) {
        return@run flowOf(null)
    }

    val item = SettingIi(
        search = SettingIi.Search(
            group = "clear",
            tokens = listOf(
                "vault",
                "clear",
            ),
        ),
    ) {
        SettingVaultClear(
            onClick = {
                clearData()
                    .launchIn(windowCoroutineScope)
            },
        )
    }
    flowOf(item)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingVaultClear(
    onClick: () -> Unit,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.DeleteForever,
        title = stringResource(Res.string.pref_item_clear_vault_title),
        onClick = onClick,
    )
}
