package com.artemchep.keyguard.feature.home.settings.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.autofill.AutofillServiceStatus
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

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
                platformWarning = platformWarning,
            )
        }
    }

@Composable
private fun SettingAutofill(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    platformWarning: AutofillPlatformWarning?,
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
                text = stringResource(Res.string.pref_item_autofill_service_title),
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.pref_item_autofill_service_text),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )

    ExpandedIfNotEmpty(
        valueOrNull = platformWarning.takeIf { checked },
    ) {
        SettingAutofillPlatformWarning(
            platformWarning = it,
        )
    }
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
                    Uri.parse("package:$packageName"),
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
