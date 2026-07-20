package com.artemchep.keyguard.feature.home.settings.component

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.autofill.AutofillServiceStatus
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.vault.component.SmartBadge
import com.artemchep.keyguard.feature.home.vault.component.SmartBadgeListContainer
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.theme.warning
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

actual fun settingAutofillProvider(
    directDI: DirectDI,
): SettingComponent = settingAutofillProvider(
    autofillService = directDI.instance(),
    appContext = directDI.instance(),
    showMessage = directDI.instance(),
)

fun settingAutofillProvider(
    autofillService: AutofillService,
    appContext: LeContext,
    showMessage: ShowMessage,
): SettingComponent =
    combine(
        autofillService
            .status(),
        flowOfBrowserAutofillStatuses(context = appContext.context),
    ) { status, browserAutofillStatuses ->
        val platformWarning = when {
            isMiui() -> AutofillPlatformWarning.Miui
            else -> null
        }

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
                footer = {
                    ExpandedIfNotEmpty(
                        valueOrNull = platformWarning
                            .takeIf { enabled },
                    ) {
                        SettingAutofillPlatformWarning(
                            platformWarning = it,
                        )
                    }

                    ExpandedIfNotEmpty(
                        valueOrNull = browserAutofillStatuses.takeIf { it.isNotEmpty() },
                    ) {
                        SettingAutofillBrowserAutofillInfo(
                            browserAutofillStatuses = it,
                            showMessage = showMessage,
                        )
                    }
                },
            )
        }
    }

@Composable
private fun SettingAutofill(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    footer: @Composable ColumnScope.() -> Unit,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.AutoAwesome,
        title = stringResource(Res.string.pref_item_autofill_service_title),
        text = stringResource(Res.string.pref_item_autofill_service_text),
        footer = footer,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun SettingAutofillPlatformWarning(
    platformWarning: AutofillPlatformWarning,
) = when (platformWarning) {
    is AutofillPlatformWarning.Miui -> {
        SettingAutofillPlatformWarningMiui(
            platformWarning = platformWarning,
        )
    }
}

@Composable
private fun SettingAutofillPlatformWarningMiui(
    platformWarning: AutofillPlatformWarning.Miui,
) {
    FlatSimpleNote(
        modifier = Modifier
            .padding(
                top = 8.dp,
                bottom = 8.dp,
                start = Dimens.horizontalPadding * 1 + 24.dp,
            ),
        type = SimpleNote.Type.INFO,
        text = stringResource(Res.string.pref_item_autofill_service_xiaomi_permission_note),
        trailing = {
            val updatedContext by rememberUpdatedState(LocalContext.current)
            IconButton(
                onClick = {
                    AutofillPlatformWarning.Miui.launchPermissionSettings(updatedContext)
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
private fun SettingAutofillBrowserAutofillInfo(
    browserAutofillStatuses: List<BrowserAutofillStatus>,
    showMessage: ShowMessage,
) {
    FlatSimpleNote(
        modifier = Modifier
            .padding(
                top = 8.dp,
                bottom = 8.dp,
                start = Dimens.horizontalPadding * 1 + 24.dp,
            ),
        type = SimpleNote.Type.INFO,
        title = stringResource(Res.string.pref_item_autofill_service_browser_autofill_v2_title),
        text = stringResource(Res.string.pref_item_autofill_service_browser_autofill_v2_text),
        content = {
            val context by rememberUpdatedState(LocalContext.current)
            val errorTitle = stringResource(
                Res.string.pref_item_autofill_service_browser_autofill_settings_error,
            )
            SmartBadgeListContainer(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                browserAutofillStatuses.forEach { browserAutofillStatus ->
                    SettingAutofillBrowserStatusRow(
                        browserAutofillStatus = browserAutofillStatus,
                        onClick = {
                            try {
                                context.launchBrowserAutofillSettings(
                                    browserAutofillStatus.target,
                                )
                            } catch (e: Exception) {
                                showMessage.copy(
                                    ToastMessage(
                                        type = ToastMessage.Type.ERROR,
                                        title = errorTitle,
                                        text = e.localizedMessage,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun SettingAutofillBrowserStatusRow(
    browserAutofillStatus: BrowserAutofillStatus,
    onClick: () -> Unit,
) {
    val statusColor = if (browserAutofillStatus.isEnabled) {
        MaterialTheme.colorScheme.ok
    } else {
        MaterialTheme.colorScheme.warning
    }
    SmartBadge(
        icon = {
            Icon(
                imageVector = if (browserAutofillStatus.isEnabled) {
                    Icons.Outlined.CheckCircleOutline
                } else {
                    Icons.Outlined.Circle
                },
                contentDescription = null,
                tint = statusColor,
            )
        },
        title = browserAutofillStatus.target.displayName,
        text = stringResource(
            if (browserAutofillStatus.isEnabled) {
                Res.string.enabled
            } else {
                Res.string.disabled
            },
        ),
        onClick = onClick,
    )
}

private sealed interface AutofillPlatformWarning {
    data object Miui : AutofillPlatformWarning {
        fun launchPermissionSettings(
            context: Context,
        ) {
            val packageName = context.packageName
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
                putExtra("extra_pkgname", packageName)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                val genericIntent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:$packageName".toUri(),
                )
                kotlin.runCatching {
                    context.startActivity(genericIntent)
                }
            }
        }
    }
}

private fun isMiui(): Boolean {
    return !getSystemProperty("ro.miui.ui.version.name").isNullOrBlank()
}

private fun getSystemProperty(propName: String): String? {
    val line: String
    var input: BufferedReader? = null
    try {
        val p = Runtime.getRuntime().exec("getprop $propName")
        input = BufferedReader(InputStreamReader(p.inputStream), 1024)
        line = input.readLine()
        input.close()
    } catch (ex: IOException) {
        return null
    } finally {
        if (input != null) {
            try {
                input.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
    return line
}
