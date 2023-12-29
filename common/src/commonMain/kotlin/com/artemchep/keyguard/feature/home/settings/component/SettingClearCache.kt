package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesLocalDataSource
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageDataSourceLocal
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.vault.component.VaultViewButtonItem
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.flow.flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingClearCache(
    directDI: DirectDI,
) = settingClearCache(
    breachesLocalDataSource = directDI.instance(),
//    accountPwnageDataSourceLocal = directDI.instance(),
    passwordPwnageDataSourceLocal = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingClearCache(
    breachesLocalDataSource: BreachesLocalDataSource,
//    accountPwnageDataSourceLocal: AccountPwnageDataSourceLocal,
    passwordPwnageDataSourceLocal: PasswordPwnageDataSourceLocal,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = flow {
    val component = if (!isRelease) {
        SettingIi {
            SettingClearCache(
                onClick = {
                    listOf(
                        breachesLocalDataSource.clear(),
//                        accountPwnageDataSourceLocal.clear(),
                        passwordPwnageDataSourceLocal.clear(),
                    )
                        .parallel(parallelism = 1)
                        .launchIn(windowCoroutineScope)
                },
            )
        }
    } else {
        null
    }
    emit(component)
}

@Composable
private fun SettingClearCache(
    onClick: (() -> Unit),
) {
    VaultViewButtonItem(
        leading = icon<RowScope>(Icons.Outlined.Cached, Icons.Outlined.Remove),
        text = "Clear cache",
        onClick = onClick,
    )
}
