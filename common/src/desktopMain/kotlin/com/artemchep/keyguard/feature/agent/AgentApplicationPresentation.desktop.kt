package com.artemchep.keyguard.feature.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.artemchep.keyguard.common.service.agent.AgentCallerIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.filechooser.FileSystemView

private const val MAX_APPLICATION_PATH_LENGTH = 4_096

@Composable
internal actual fun rememberAgentApplicationPresentation(
    caller: AgentCallerIdentity?,
): AgentApplicationPresentation {
    val appName = caller?.appName.orEmpty().trim().takeIf(String::isNotEmpty)
    val bundlePath = caller?.appBundlePath.orEmpty()
    val executablePath = caller?.executablePath.orEmpty()
    val lookup = desktopApplicationLookup(
        bundlePath = bundlePath,
        executablePath = executablePath,
    )
    val initial = AgentApplicationPresentation(displayName = appName)
    val presentation by produceState(
        initialValue = initial,
        key1 = lookup?.path,
        key2 = appName,
    ) {
        if (lookup == null) {
            value = initial
            return@produceState
        }

        value = withContext(Dispatchers.IO) {
            resolveDesktopApplicationPresentation(
                lookup = lookup,
                fallbackName = appName,
            )
        }
    }
    return presentation
}

/**
 * Accept only bounded absolute paths. The caller-controlled display fields may
 * be malformed, and native shell icon providers must never receive NULs or
 * relative paths from an agent request.
 */
private fun desktopApplicationLookup(
    bundlePath: String,
    executablePath: String,
): DesktopApplicationLookup? {
    val bundle = bundlePath.toSafeAbsoluteFileOrNull()
    if (bundle != null) {
        return DesktopApplicationLookup(
            file = bundle,
            path = bundle.path,
            maySupplyDisplayName = true,
        )
    }
    val executable = executablePath.toSafeAbsoluteFileOrNull()
        ?: return null
    return DesktopApplicationLookup(
        file = executable,
        path = executable.path,
        // An executable label such as "ssh" is not an application label.
        maySupplyDisplayName = false,
    )
}

private fun String.toSafeAbsoluteFileOrNull(): File? {
    if (isBlank() || length > MAX_APPLICATION_PATH_LENGTH || '\u0000' in this) {
        return null
    }
    return File(this).takeIf(File::isAbsolute)
}

private fun resolveDesktopApplicationPresentation(
    lookup: DesktopApplicationLookup,
    fallbackName: String?,
): AgentApplicationPresentation {
    val fileSystemView = runCatching(FileSystemView::getFileSystemView).getOrNull()
        ?: return AgentApplicationPresentation(displayName = fallbackName)
    val displayName = if (lookup.maySupplyDisplayName) {
        runCatching { fileSystemView.getSystemDisplayName(lookup.file) }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    } else {
        null
    }

    return AgentApplicationPresentation(
        displayName = displayName ?: fallbackName,
        icon = null, // we can not reliably resolve the icon yet
    )
}

private data class DesktopApplicationLookup(
    val file: File,
    val path: String,
    val maySupplyDisplayName: Boolean,
)
