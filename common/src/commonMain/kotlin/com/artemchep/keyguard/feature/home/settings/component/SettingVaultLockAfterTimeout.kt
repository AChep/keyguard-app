package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeout
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeoutVariants
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterTimeout
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.format
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

fun settingVaultLockAfterTimeoutProvider(
    directDI: DirectDI,
) = settingVaultLockAfterTimeoutProvider(
    getVaultLockAfterTimeout = directDI.instance(),
    getVaultLockAfterTimeoutVariants = directDI.instance(),
    putVaultLockAfterTimeout = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
    context = directDI.instance(),
)

fun settingVaultLockAfterTimeoutProvider(
    getVaultLockAfterTimeout: GetVaultLockAfterTimeout,
    getVaultLockAfterTimeoutVariants: GetVaultLockAfterTimeoutVariants,
    putVaultLockAfterTimeout: PutVaultLockAfterTimeout,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getVaultLockAfterTimeout(),
    getVaultLockAfterTimeoutVariants(),
) { timeout, variants ->
    val text = getLockAfterDurationTitle(timeout, context)
    val dropdown = variants
        .map { duration ->
            val actionSelected = duration == timeout
            val actionTitle = getLockAfterDurationTitle(duration, context)
            FlatItemAction(
                title = TextHolder.Value(actionTitle),
                selected = actionSelected,
                onClick = {
                    putVaultLockAfterTimeout(duration)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

    SettingIi(
        search = SettingIi.Search(
            group = "lock",
            tokens = listOf(
                "vault",
                "lock",
                "timeout",
            ),
        ),
    ) {
        SettingLockAfterTimeout(
            text = text,
            dropdown = dropdown,
        )
    }
}

private suspend fun getLockAfterDurationTitle(duration: Duration, context: LeContext) = when (duration) {
    Duration.ZERO -> textResource(
        Res.string.pref_item_lock_vault_after_delay_immediately_text,
        context,
    )

    Duration.INFINITE -> textResource(
        Res.string.pref_item_lock_vault_after_delay_never_text,
        context,
    )

    else -> duration.format(context)
}

@Composable
fun SettingLockAfterTimeout(
    text: String,
    dropdown: List<FlatItemAction>,
) {
    FlatDropdown(
        leading = icon<RowScope>(Icons.Outlined.Timer),
        dropdown = dropdown,
        content = {
            FlatItemTextContent(
                title = {
                    Text(stringResource(Res.string.pref_item_lock_vault_after_delay_title))
                },
                text = {
                    Text(text)
                },
            )
        },
    )
}
