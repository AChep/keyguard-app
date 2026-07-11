package com.artemchep.keyguard.feature.agent

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import com.artemchep.keyguard.common.service.agent.AgentCallerIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_PACKAGE_NAME_LENGTH = 255
private const val APPLICATION_ICON_EDGE_PX = 64

private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

@Composable
internal actual fun rememberAgentApplicationPresentation(
    caller: AgentCallerIdentity?,
): AgentApplicationPresentation {
    val context = LocalContext.current
    val appName = caller?.appName.orEmpty().trim().takeIf(String::isNotEmpty)
    val packageName = caller?.appBundlePath
        ?.trim()
        ?.takeIf {
            it.length <= MAX_PACKAGE_NAME_LENGTH && PACKAGE_NAME_PATTERN.matches(it)
        }
    val initial = AgentApplicationPresentation(displayName = appName)
    val presentation by produceState(
        initialValue = initial,
        key1 = packageName,
        key2 = appName,
    ) {
        if (packageName == null) {
            value = initial
            return@produceState
        }

        value = withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val applicationInfo = try {
                packageManager.getApplicationInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            if (applicationInfo == null) {
                initial
            } else {
                val displayName = runCatching {
                    applicationInfo.loadLabel(packageManager).toString().trim()
                }.getOrNull()?.takeIf(String::isNotEmpty)
                val icon = runCatching {
                    applicationInfo.loadIcon(packageManager)
                        .toBitmap(
                            width = APPLICATION_ICON_EDGE_PX,
                            height = APPLICATION_ICON_EDGE_PX,
                        )
                        .asImageBitmap()
                }.getOrNull()
                AgentApplicationPresentation(
                    displayName = displayName ?: appName,
                    icon = icon,
                )
            }
        }
    }
    return presentation
}
