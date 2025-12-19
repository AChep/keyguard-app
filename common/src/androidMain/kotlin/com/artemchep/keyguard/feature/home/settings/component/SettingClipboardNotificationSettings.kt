package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNotifications
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.artemchep.keyguard.android.clipboard.KeyguardClipboardService
import com.artemchep.keyguard.android.util.launchNotificationChannelSettingsOrThrow
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import org.kodein.di.compose.rememberInstance

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
    val showMessage by rememberInstance<ShowMessage>()
    val clipboardService by rememberInstance<ClipboardService>()

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
            // Make sure that the notification channel is created before
            // attempting to launch the notification channel settings.
            val channelId = updatedContext
                .getString(R.string.notification_clipboard_channel_id)
            KeyguardClipboardService.createNotificationChannel(
                context = updatedContext,
                clipboardService = clipboardService,
            )

            try {
                updatedContext.launchNotificationChannelSettingsOrThrow(channelId)
            } catch (e: Exception) {
                val msg = ToastMessage(
                    type = ToastMessage.Type.ERROR,
                    title = "Failed to launch the Settings app",
                    text = e.message,
                )
                showMessage.copy(msg)
            }
        },
    )
}
