package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetClipboardAutoRefresh
import com.artemchep.keyguard.common.usecase.GetClipboardAutoRefreshVariants
import com.artemchep.keyguard.common.usecase.PutClipboardAutoRefresh
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.format
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

fun settingClipboardAutoRefreshProvider(
    directDI: DirectDI,
) = settingClipboardAutoRefreshProvider(
    getClipboardAutoRefresh = directDI.instance(),
    getClipboardAutoRefreshVariants = directDI.instance(),
    putClipboardAutoRefresh = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
    context = directDI.instance(),
)

fun settingClipboardAutoRefreshProvider(
    getClipboardAutoRefresh: GetClipboardAutoRefresh,
    getClipboardAutoRefreshVariants: GetClipboardAutoRefreshVariants,
    putClipboardAutoRefresh: PutClipboardAutoRefresh,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getClipboardAutoRefresh(),
    getClipboardAutoRefreshVariants(),
) { timeout, variants ->
    val text = getAutoRefreshDurationTitle(timeout, context)
    val dropdown = variants
        .map { duration ->
            val actionSelected = timeout == duration
            val actionTitle = getAutoRefreshDurationTitle(duration, context)
            FlatItemAction(
                title = TextHolder.Value(actionTitle),
                selected = actionSelected,
                onClick = {
                    putClipboardAutoRefresh(duration)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

    SettingIi(
        platformClass = Platform.Mobile::class,
    ) {
        SettingClipboardAutoRefresh(
            text = text,
            dropdown = dropdown,
        )
    }
}

private suspend fun getAutoRefreshDurationTitle(duration: Duration, context: LeContext) = when (duration) {
    Duration.ZERO -> textResource(
        Res.string.pref_item_clipboard_auto_refresh_otp_duration_never_text,
        context,
    )

    else -> duration.format(context)
}

@Composable
private fun SettingClipboardAutoRefresh(
    text: String,
    dropdown: List<FlatItemAction>,
) {
    FlatDropdownSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Outlined.KeyguardTwoFa, Icons.Outlined.Notifications),
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_clipboard_auto_refresh_otp_duration_title),
                    )
                },
                text = {
                    Text(text)
                    Spacer(
                        modifier = Modifier
                            .height(8.dp),
                    )
                    Text(
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                        style = MaterialTheme.typography.bodySmall,
                        text = stringResource(Res.string.pref_item_clipboard_auto_refresh_otp_duration_note),
                    )
                },
            )
        },
        dropdown = dropdown,
    )
}
