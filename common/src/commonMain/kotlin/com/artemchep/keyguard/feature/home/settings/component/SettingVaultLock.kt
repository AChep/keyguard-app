package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.vault.component.VaultViewButtonItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingVaultLockProvider(
    directDI: DirectDI,
) = settingVaultLockProvider(
    clearVaultSession = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingVaultLockProvider(
    clearVaultSession: ClearVaultSession,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = kotlin.run {
    val item = SettingIi(
        search = SettingIi.Search(
            group = "lock",
            tokens = listOf(
                "vault",
                "lock",
            ),
        ),
    ) {
        SettingVaultLock(
            onClick = {
                clearVaultSession()
                    .launchIn(windowCoroutineScope)
            },
        )
    }
    flowOf(item)
}

@Composable
fun SettingVaultLock(
    onClick: () -> Unit,
) {
    VaultViewButtonItem(
        leading = icon<RowScope>(Icons.Outlined.Lock),
        text = stringResource(Res.strings.pref_item_lock_vault_title),
        onClick = onClick,
    )
}
