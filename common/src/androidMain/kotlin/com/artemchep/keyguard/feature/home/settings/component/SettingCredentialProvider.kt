package com.artemchep.keyguard.feature.home.settings.component

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import org.kodein.di.instance

actual fun settingCredentialProviderProvider(
    directDI: DirectDI,
): SettingComponent = if (Build.VERSION.SDK_INT >= 34) {
    settingCredentialProviderProvider(
        windowCoroutineScope = directDI.instance(),
    )
} else {
    // Credential provider is not available on the
    // older platform versions.
    flowOf(null)
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun settingCredentialProviderProvider(
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = kotlin.run {
    val item = SettingIi(
        search = SettingIi.Search(
            group = "autofill",
            tokens = listOf(
                "credential",
                "passkey",
                "fido",
                "provider",
                "password",
            ),
        ),
    ) {
        val context = LocalContext.current
        val pi = remember(context) {
            CredentialManager
                .create(context)
                .createSettingsPendingIntent()
        }
        SettingCredentialProvider(
            onClick = {
                ioEffect(Dispatchers.Main.immediate) {
                    pi.send()
                }.launchIn(windowCoroutineScope)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingCredentialProvider(
    onClick: (() -> Unit)?,
) {
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.Key),
        trailing = {
            ChevronIcon()
        },
        title = {
            Text(
                text = stringResource(Res.string.pref_item_credential_provider_title),
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.pref_item_credential_provider_text),
            )
        },
        onClick = onClick,
    )
}