package com.artemchep.keyguard.desktop.startup

import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.util.launchatlogin.LaunchAtLogin
import java.awt.EventQueue
import java.awt.Toolkit

/** Detects a login launch from the launch AppleEvent before AWT consumes it. */
@Suppress("TooGenericExceptionCaught") // Startup detection must not prevent manual recovery after a broken update.
internal fun macosLoginActivation(): Boolean {
    val installed = !System.getProperty("compose.application.resources.dir").isNullOrBlank()
    if (!installed || CurrentPlatform !is Platform.Desktop.MacOS) return false
    return try {
        LaunchAtLogin.prepareLaunchDetection()
        // LWCToolkit waits for NSApplicationDidFinishLaunching before returning.
        Toolkit.getDefaultToolkit()
        EventQueue.invokeAndWait { }
        LaunchAtLogin.launchedAtLogin()
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (error: LinkageError) {
        System.err.println("Could not load launch-at-login bridge: ${error.javaClass.simpleName}")
        false
    } catch (error: Exception) {
        System.err.println("Could not determine login activation: ${error.javaClass.simpleName}")
        false
    }
}
