package com.artemchep.keyguard.feature.home.settings.component

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import arrow.core.getOrElse
import com.artemchep.keyguard.android.clipboard.KeyguardClipboardService
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.feature.home.vault.component.VaultViewButtonItem
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import org.kodein.di.instance

actual fun settingEmitTotpProvider(
    directDI: DirectDI,
): SettingComponent = settingEmitTotpProvider(
    context = directDI.instance<Application>(),
)

fun settingEmitTotpProvider(
    context: Context,
): SettingComponent = kotlin.run {
    val totp = TotpToken.parse("keyguard")
        .getOrElse { e ->
            throw e
        }

    val item = SettingIi {
        SettingEmitTotp(
            onClick = {
                val intent = KeyguardClipboardService.getIntent(
                    context = context,
                    cipherName = "Test cipher",
                    totpToken = totp,
                    autoCopy = false,
                )
                context.startForegroundService(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
fun SettingEmitTotp(
    onClick: () -> Unit,
) {
    VaultViewButtonItem(
        leading = icon<RowScope>(Icons.Outlined.KeyguardTwoFa),
        text = "Emit TOTP",
        onClick = onClick,
    )
}
