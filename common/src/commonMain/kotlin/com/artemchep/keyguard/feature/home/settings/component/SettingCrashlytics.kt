package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.crashlyticsIsEnabledFlow
import com.artemchep.keyguard.platform.crashlyticsSetEnabled
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
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
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.BugReport,
        title = stringResource(Res.string.pref_item_send_crash_reports_title),
        checked = checked == true,
        onCheckedChange = onCheckedChange,
    )
}
