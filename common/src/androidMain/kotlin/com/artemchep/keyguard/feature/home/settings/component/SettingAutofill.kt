package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import arrow.core.partially1
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.autofill.AutofillServiceStatus
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

actual fun settingAutofillProvider(
    directDI: DirectDI,
): SettingComponent = settingAutofillProvider(
    autofillService = directDI.instance(),
)

fun settingAutofillProvider(
    autofillService: AutofillService,
): SettingComponent = autofillService
    .status()
    .map { status ->
        // composable
        SettingIi(
            search = SettingIi.Search(
                group = "autofill",
                tokens = listOf(
                    "autofill",
                ),
            ),
        ) {
            val disabled = status is AutofillServiceStatus.Disabled && status.onEnable == null ||
                    status is AutofillServiceStatus.Enabled && status.onDisable == null
            val enabled = status is AutofillServiceStatus.Enabled
            val context by rememberUpdatedState(LocalContext.current)
            SettingAutofill(
                checked = enabled,
                onCheckedChange = if (!disabled) {
                    // lambda
                    lambda@{ shouldBeEnabled ->
                        val activity = context.closestActivityOrNull
                            ?: return@lambda
                        when {
                            shouldBeEnabled && status is AutofillServiceStatus.Disabled ->
                                status.onEnable?.invoke(activity)

                            !shouldBeEnabled && status is AutofillServiceStatus.Enabled ->
                                status.onDisable?.invoke()
                        }
                    }
                } else {
                    null
                },
            )
        }
    }

@Composable
private fun SettingAutofill(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.AutoAwesome),
        trailing = {
            Switch(
                checked = checked,
                enabled = onCheckedChange != null,
                onCheckedChange = onCheckedChange,
            )
        },
        title = {
            Text(
                text = stringResource(Res.strings.pref_item_autofill_service_title),
            )
        },
        text = {
            Text(
                text = stringResource(Res.strings.pref_item_autofill_service_text),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
