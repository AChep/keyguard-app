package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import arrow.core.partially1
import com.artemchep.keyguard.platform.crashlyticsIsEnabledFlow
import com.artemchep.keyguard.platform.crashlyticsSetEnabled
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.IconBox
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI

fun settingCrashlyticsProvider(
    directDI: DirectDI,
): SettingComponent = run {
    crashlyticsIsEnabledFlow()
        .map { checked ->
            val onCheckedChange = { shouldCheck: Boolean ->
                crashlyticsSetEnabled(shouldCheck)
            }

            SettingIi(
                search = SettingIi.Search(
                    group = "analytics",
                    tokens = listOf(
                        "crash",
                        "report",
                        "analytics",
                    ),
                ),
            ) {
                SettingCrashlytics(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingCrashlytics(
    checked: Boolean?,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItem(
        leading = {
            val imageVector = Icons.Outlined.BugReport
            IconBox(imageVector)
        },
        trailing = {
            ExpandedIfNotEmptyForRow(
                valueOrNull = checked,
            ) {
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentEnforcement provides false,
                ) {
                    Switch(
                        checked = it,
                        enabled = onCheckedChange != null,
                        onCheckedChange = onCheckedChange,
                    )
                }
            }
        },
        title = {
            Text(
                text = stringResource(Res.string.pref_item_send_crash_reports_title),
            )
        },
        onClick = onCheckedChange?.partially1(checked != true),
    )
}
