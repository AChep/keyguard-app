package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetClipboardAutoClear
import com.artemchep.keyguard.common.usecase.GetClipboardAutoClearVariants
import com.artemchep.keyguard.common.usecase.PutClipboardAutoClear
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.format
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

fun settingClipboardAutoClearProvider(
    directDI: DirectDI,
) = settingClipboardAutoClearProvider(
    getClipboardAutoClear = directDI.instance(),
    getClipboardAutoClearVariants = directDI.instance(),
    putClipboardAutoClear = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
    context = directDI.instance(),
)

fun settingClipboardAutoClearProvider(
    getClipboardAutoClear: GetClipboardAutoClear,
    getClipboardAutoClearVariants: GetClipboardAutoClearVariants,
    putClipboardAutoClear: PutClipboardAutoClear,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getClipboardAutoClear(),
    getClipboardAutoClearVariants(),
) { timeout, variants ->
    val text = getAutoClearDurationTitle(timeout, context)
    val dropdown = variants
        .map { duration ->
            val title = getAutoClearDurationTitle(duration, context)
            FlatItemAction(
                title = title,
                onClick = {
                    putClipboardAutoClear(duration)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

    SettingIi(
        platformClass = Platform.Mobile::class,
    ) {
        SettingClipboardAutoClear(
            text = text,
            dropdown = dropdown,
        )
    }
}

private fun getAutoClearDurationTitle(duration: Duration, context: LeContext) = when (duration) {
    Duration.ZERO -> textResource(
        Res.strings.pref_item_clipboard_auto_clear_immediately_text,
        context,
    )

    Duration.INFINITE -> textResource(
        Res.strings.pref_item_clipboard_auto_clear_never_text,
        context,
    )

    else -> duration.format(context)
}

@Composable
private fun SettingClipboardAutoClear(
    text: String,
    dropdown: List<FlatItemAction>,
) {
    FlatDropdown(
        leading = icon<RowScope>(Icons.Outlined.ContentPaste, Icons.Outlined.Clear),
        dropdown = dropdown,
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.strings.pref_item_clipboard_auto_clear_title),
                    )
                },
                text = {
                    Text(text)
                },
            )
        },
    )
}
