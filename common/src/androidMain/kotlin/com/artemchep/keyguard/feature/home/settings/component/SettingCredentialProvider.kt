package com.artemchep.keyguard.feature.home.settings.component

import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
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
        val hasFeature = remember(context) {
            val pm = context.packageManager
            pm.hasSystemFeature(PackageManager.FEATURE_CREDENTIALS)
        }
        SettingCredentialProvider(
            onClick = if (hasFeature) {
                // lambda
                {
                    ioEffect(Dispatchers.Main.immediate) {
                        pi.send()
                    }.launchIn(windowCoroutineScope)
                }
            } else {
                null
            },
            hasFeature = hasFeature,
        )
    }
    flowOf(item)
}

@Composable
private fun SettingCredentialProvider(
    onClick: (() -> Unit)?,
    hasFeature: Boolean,
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
    if (!hasFeature) {
        FlatSimpleNote(
            modifier = Modifier
                .padding(
                    top = 8.dp,
                    bottom = 8.dp,
                    start = Dimens.horizontalPadding * 1 + 24.dp,
                ),
            type = SimpleNote.Type.INFO,
            text = stringResource(Res.string.pref_item_credential_provider_no_feature_note),
        )
    }
}