package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MobileOff
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingVaultLockAfterScreenOffProvider(
    directDI: DirectDI,
) = settingVaultLockAfterScreenOffProvider(
    getVaultPersist = directDI.instance(),
    getVaultLockAfterScreenOff = directDI.instance(),
    putVaultLockAfterScreenOff = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingVaultLockAfterScreenOffProvider(
    getVaultPersist: GetVaultPersist,
    getVaultLockAfterScreenOff: GetVaultLockAfterScreenOff,
    putVaultLockAfterScreenOff: PutVaultLockAfterScreenOff,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    getVaultPersist(),
    getVaultLockAfterScreenOff(),
) { persist, screenLock ->
    val onCheckedChange = { shouldScreenLock: Boolean ->
        putVaultLockAfterScreenOff(shouldScreenLock)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        platformClasses = listOf(
            Platform.Mobile::class,
        ),
        search = SettingIi.Search(
            group = "lock",
            tokens = listOf(
                "vault",
                "lock",
                "screen",
            ),
        ),
    ) {
        SettingLockAfterScreenOff(
            checked = screenLock,
            onCheckedChange = onCheckedChange.takeUnless { persist },
        )
    }
}

@Composable
private fun SettingLockAfterScreenOff(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.MobileOff,
        title = stringResource(Res.string.pref_item_lock_vault_after_screen_off_title),
        text = stringResource(Res.string.pref_item_lock_vault_after_screen_off_text),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
