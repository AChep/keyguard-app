package com.artemchep.keyguard.desktop.startup

import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginRegistration
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginService
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginStatus
import com.artemchep.keyguard.common.service.startup.impl.LaunchAtLoginServiceImpl
import com.artemchep.keyguard.presentation.startup.DesktopLaunchReason
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_launch_at_login_reason
import com.artemchep.keyguard.util.launchatlogin.LaunchAtLogin
import com.artemchep.keyguard.util.launchatlogin.LaunchAtLoginTarget
import com.artemchep.keyguard.util.launchatlogin.LaunchAtLoginStatus as NativeLaunchAtLoginStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.resources.getString

internal fun NativeLaunchAtLoginStatus.toLaunchAtLoginStatus() = LaunchAtLoginStatus.valueOf(name)

private class NativeDesktopLoginRegistration(private val registration: LaunchAtLogin) : LaunchAtLoginRegistration {
    override suspend fun status() = linkageAsException { registration.status().toLaunchAtLoginStatus() }
    override suspend fun setEnabled(enabled: Boolean) =
        linkageAsException { registration.setEnabled(enabled).toLaunchAtLoginStatus() }
    override suspend fun openSettings() = linkageAsException { registration.openSettings() }
}

internal class DesktopLaunchAtLoginService(
    logRepository: LogRepository,
    createRegistration: suspend () -> LaunchAtLoginRegistration? = ::createDesktopLoginRegistration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LaunchAtLoginService by LaunchAtLoginServiceImpl(
    createRegistration = { linkageAsException { createRegistration() } },
    logRepository = logRepository,
    dispatcher = dispatcher,
)

/** A missing or incompatible bundled library is reported as an error in the settings row. */
private inline fun <T> linkageAsException(block: () -> T): T = try {
    block()
} catch (error: LinkageError) {
    throw IllegalStateException(error)
}

private suspend fun createDesktopLoginRegistration(): LaunchAtLoginRegistration? {
    // Development Java/Gradle launchers are not stable installed application entry points.
    if (System.getProperty("compose.application.resources.dir").isNullOrBlank()) return null
    val target = installedDesktopLoginTarget(reason = getString(Res.string.pref_item_launch_at_login_reason))
    return target?.let { NativeDesktopLoginRegistration(LaunchAtLogin(it)) }
}

/** This installation's entry point; the Flatpak permission prompt shows [reason]. */
internal fun installedDesktopLoginTarget(reason: String): LaunchAtLoginTarget? {
    val launcher = System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank)
        ?.let(Path::of)?.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
    val appImage = System.getenv("APPIMAGE")?.takeIf(String::isNotBlank)
        ?.let(Path::of)?.takeIf { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) }
    return desktopLoginTarget(
        osName = System.getProperty("os.name"),
        launcher = launcher?.toAbsolutePath()?.normalize()?.toString(),
        appImage = appImage?.toString(),
        isAppImageMount = !System.getenv("APPDIR").isNullOrBlank(),
        flatpakId = System.getenv("FLATPAK_ID"),
        reason = reason,
    )
}

/** Resolves package entry points without guessing from the JVM's executable or temporary mounts. */
internal fun desktopLoginTarget(
    osName: String,
    launcher: String?,
    appImage: String?,
    isAppImageMount: Boolean,
    flatpakId: String?,
    reason: String,
): LaunchAtLoginTarget? {
    val arguments = listOf(DesktopLaunchReason.LOGIN_ARGUMENT)
    return when {
        osName.startsWith("Mac", ignoreCase = true) ->
            launcher?.let { LaunchAtLoginTarget.MacOSMainApp }
        // A packaged (MSIX) process uses the manifest's startup task instead of a shortcut.
        osName.startsWith("Windows", ignoreCase = true) -> launcher?.let {
            LaunchAtLoginTarget.WindowsStartup(
                id = "com.artemchep.keyguard",
                name = "Keyguard",
                executable = it,
                taskId = "KeyguardStartup",
                arguments = arguments,
            )
        }
        osName.startsWith("Linux", ignoreCase = true) -> when {
            !flatpakId.isNullOrBlank() -> LaunchAtLoginTarget.FlatpakBackground(
                command = listOf("Keyguard") + arguments,
                reason = reason,
            )
            // A temporary AppImage mount must never become the registered executable.
            isAppImageMount && appImage == null -> null
            else -> (appImage ?: launcher)?.let {
                LaunchAtLoginTarget.XdgAutostart("com.artemchep.keyguard", "Keyguard", it, arguments)
            }
        }
        else -> null
    }
}
