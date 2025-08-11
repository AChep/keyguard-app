package com.artemchep.keyguard.feature.home.settings.component

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNotifications
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

actual fun settingClipboardNotificationSettingsProvider(
    directDI: DirectDI,
): SettingComponent = settingClipboardNotificationSettingsProvider()

fun settingClipboardNotificationSettingsProvider(
): SettingComponent = run {

    val item = SettingIi {
        SettingClipboardNotificationSettings()
    }
    flowOf(item)
}

@Composable
fun SettingClipboardNotificationSettings(
) {
    val updatedContext by rememberUpdatedState(LocalContext.current)
    FlatItemSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Outlined.EditNotifications),
        title = {
            Text(
                text = stringResource(Res.string.pref_item_clipboard_notification_settings_title),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                val packageName = updatedContext.packageName
                val channelId = updatedContext.getString(R.string.notification_clipboard_channel_id)
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            }
            updatedContext.startActivity(intent)
        },
    )
}
