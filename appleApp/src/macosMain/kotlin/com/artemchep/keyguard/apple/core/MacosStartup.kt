package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.presentation.startup.DesktopLaunchReason
import com.artemchep.keyguard.platform.appleAppGroupContainerPath
import com.artemchep.keyguard.util.instance.InstanceCoordinator
import com.artemchep.keyguard.util.instance.InstanceOwnershipConfig
import com.artemchep.keyguard.util.launchatlogin.LaunchAtLogin
import com.artemchep.keyguard.util.launchatlogin.LaunchAtLoginException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Swift-facing adapter; launch policy is shared with the JVM desktop application. */
class MacosStartup(
    arguments: List<String>,
    loginActivation: Boolean,
) {
    private val reason = DesktopLaunchReason.fromArguments(
        arguments = arguments,
        loginActivation = loginActivation,
    )

    /** The window decision depends on the user's start-in-background preference. */
    val needsStartupPreferences: Boolean = reason.showsMainWindow(null) == null
    val activatesExistingInstance: Boolean = reason.activatesExistingInstance

    fun showsMainWindow(startInBackground: Boolean): Boolean =
        reason.showsMainWindow(startInBackground) == true

    /**
     * Call before constructing vault services, on a blocking I/O dispatcher.
     * LaunchServices already reopens the same bundle, so the lock only keeps the other
     * app flavor off the shared App Group data.
     */
    @Throws(Exception::class, CancellationException::class)
    suspend fun acquireInstance(): MacosInstance? = withContext(Dispatchers.IO) {
        val group = appleAppGroupContainerPath()
        InstanceCoordinator.acquireOwnership(
            InstanceOwnershipConfig(
                // Outside Keyguard's erasable vault directory, shared by native app
                // flavors that access the same App Group data.
                coordinationDirectory = "$group/.instance",
                identity = "keyguard-native",
            ),
        )?.let(::MacosInstance)
    }

    companion object {
        /** Main thread only, while the launch AppleEvent is current (`applicationDidFinishLaunching`). */
        fun launchedAtLogin(): Boolean = try {
            LaunchAtLogin.launchedAtLogin()
        } catch (_: LaunchAtLoginException) {
            // Undetectable means an ordinary launch: the main window opens.
            false
        }
    }
}
