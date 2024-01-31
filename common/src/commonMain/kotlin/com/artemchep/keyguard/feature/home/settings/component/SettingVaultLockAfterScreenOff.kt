package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MobileOff
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
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
        platformClass = Platform.Mobile::class,
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
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.MobileOff),
        trailing = {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentEnforcement provides false,
            ) {
                Switch(
                    checked = checked,
                    enabled = onCheckedChange != null,
                    onCheckedChange = onCheckedChange,
                )
            }
        },
        title = {
            Text(stringResource(Res.strings.pref_item_lock_vault_after_screen_off_title))
        },
        text = {
            val text = stringResource(Res.strings.pref_item_lock_vault_after_screen_off_text)
            Text(text)
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
