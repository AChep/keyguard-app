package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAllowScreenshots
import com.artemchep.keyguard.common.usecase.PutAllowScreenshots
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingScreenshotsProvider(
    directDI: DirectDI,
) = settingScreenshotsProvider(
    getAllowScreenshots = directDI.instance(),
    putAllowScreenshots = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingScreenshotsProvider(
    getAllowScreenshots: GetAllowScreenshots,
    putAllowScreenshots: PutAllowScreenshots,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getAllowScreenshots().map { allowScreenshots ->
    val onCheckedChange = { shouldConcealFields: Boolean ->
        putAllowScreenshots(shouldConcealFields)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        platformClass = Platform.Mobile::class,
        search = SettingIi.Search(
            group = "conceal",
            tokens = listOf(
                "screenshot",
                "record",
                "block",
                "prevent",
            ),
        ),
    ) {
        SettingScreenshotsFields(
            checked = allowScreenshots,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingScreenshotsFields(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.Screenshot),
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
            Text(
                text = stringResource(Res.strings.pref_item_allow_screenshots_title),
            )
        },
        text = {
            val text = if (checked) {
                stringResource(Res.strings.pref_item_allow_screenshots_text_on)
            } else {
                stringResource(Res.strings.pref_item_allow_screenshots_text_off)
            }
            Text(text)
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
