package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterReboot
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterReboot
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.icons.Stub
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingVaultLockAfterRebootProvider(
    directDI: DirectDI,
) = settingVaultLockAfterRebootProvider(
    getVaultPersist = directDI.instance(),
    getVaultLockAfterReboot = directDI.instance(),
    putVaultLockAfterReboot = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingVaultLockAfterRebootProvider(
    getVaultPersist: GetVaultPersist,
    getVaultLockAfterReboot: GetVaultLockAfterReboot,
    putVaultLockAfterReboot: PutVaultLockAfterReboot,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    getVaultPersist(),
    getVaultLockAfterReboot(),
) { persist, lockAfterReboot ->
    val onCheckedChange = { shouldLockAfterReboot: Boolean ->
        putVaultLockAfterReboot(shouldLockAfterReboot)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "lock",
            tokens = listOf(
                "vault",
                "lock",
                "reboot",
            ),
        ),
    ) {
        // If the vault is not stored on a disk then it by
        // definition cannot survive a reboot.
        ExpandedIfNotEmpty(
            valueOrNull = Unit.takeIf { persist },
        ) {
            SettingLockAfterReboot(
                checked = lockAfterReboot,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun SettingLockAfterReboot(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Stub,
        title = stringResource(Res.string.pref_item_lock_vault_after_reboot_title),
        text = stringResource(Res.string.pref_item_lock_vault_after_reboot_text),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
