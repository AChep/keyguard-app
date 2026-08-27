package com.artemchep.keyguard.common.service.browseragent

import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.Window
import java.awt.KeyboardFocusManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Brings the Keyguard main window to the foreground inside the same JVM process.
 *
 * The IPC server and the Compose window share the same process. On XWayland
 * (which JBR uses under `WAYLAND_DISPLAY`), AWT `toFront()` / `requestFocus()`
 * sends the correct X11 protocol messages via XWayland. On native Wayland
 * (JBR with Wayland support), JBR handles the activation internally.
 *
 * External process approaches (`wmctrl`, KWin scripting, portal D-Bus) are
 * unreliable across desktop environments and broken by the Wayland security
 * model. Using AWT from the owning process is the most portable solution.
 *
 * When [token] is provided (from the NM host reading `XDG_ACTIVATION_TOKEN`),
 * it is injected into the process environment via reflective access to
 * `java.lang.ProcessEnvironment` so that XDG activation-aware desktops can
 * raise it correctly. The reflection approach works on HotSpot / OpenJDK /
 * JBR (the runtimes used by Keyguard).
 */
object WindowBringToFront {

    suspend operator fun invoke(): Boolean = withContext(Dispatchers.IO) {
        focusWindow(token = null)
    }

    /**
     * Same as [invoke] but sets `XDG_ACTIVATION_TOKEN` before focusing,
     * allowing Wayland-aware compositors to raise the window via the
     * activation token chain.
     */
    suspend fun withToken(token: String): Boolean = withContext(Dispatchers.IO) {
        focusWindow(token = token)
    }

    private suspend fun focusWindow(token: String?): Boolean = runCatching {
        if (token != null) {
            injectEnvVar("XDG_ACTIVATION_TOKEN", token)
        }
        // Post the focus request to the AWT Event Dispatch Thread.
        // This is the same thread that owns the Compose/JBR window.
        var success = false
        EventQueue.invokeAndWait {
            val windows = Window.getWindows()
            val target = windows.firstOrNull { w ->
                w.isDisplayable && w.isVisible
            }
            if (target != null) {
                target.isAutoRequestFocus = true
                target.toFront()
                target.requestFocusInWindow()
                target.requestFocus()
                success = true
            }
        }
        success
    }.getOrElse { false }

    /**
     * Injects [value] into the current process's environment so that
     * child processes and system calls see it. Uses reflective access
     * to `java.lang.ProcessEnvironment` (HotSpot / JBR internals).
     */
    private fun injectEnvVar(name: String, value: String) {
        runCatching {
            val clazz = Class.forName("java.lang.ProcessEnvironment")
            val field = clazz.getDeclaredField("theEnvironment")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val env = field.get(null) as MutableMap<String, String>
            env[name] = value
        }
    }
}
