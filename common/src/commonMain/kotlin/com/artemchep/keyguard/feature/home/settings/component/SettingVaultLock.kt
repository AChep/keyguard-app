package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.VaultViewButtonItem
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
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
                val reason = TextHolder.Res(Res.string.lock_reason_manually)
                clearVaultSession(LockReason.LOCK, reason)
                    .launchIn(windowCoroutineScope)
            },
        )
    }
    flowOf(item)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingVaultLock(
    onClick: () -> Unit,
) {
    FlatItemSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Outlined.Lock),
        title = {
            Text(
                text = stringResource(Res.string.pref_item_lock_vault_title),
            )
        },
        onClick = onClick,
    )
}
