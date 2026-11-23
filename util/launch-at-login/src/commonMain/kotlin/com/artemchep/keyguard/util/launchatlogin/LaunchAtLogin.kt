package com.artemchep.keyguard.util.launchatlogin

internal const val LAUNCH_REQUEST_MAX_LENGTH = 65_536
private const val MAX_TARGET_FIELDS = 68
private const val OPERATION_STATUS = 0
private const val OPERATION_ENABLE = 1
private const val OPERATION_DISABLE = 2
private const val OPERATION_SETTINGS = 3
private const val OPERATION_LOGIN_EVENT = 5
private const val OPERATION_PREPARE_LOGIN_DETECTION = 6

/** The installed entry point to register. */
sealed interface LaunchAtLoginTarget {
    data object MacOSMainApp : LaunchAtLoginTarget

    data class XdgAutostart(
        val id: String,
        val name: String,
        val executable: String,
        val arguments: List<String> = emptyList(),
    ) : LaunchAtLoginTarget

    /** A packaged (MSIX) process uses its manifest's [taskId]; otherwise a Startup-folder shortcut. */
    data class WindowsStartup(
        val id: String,
        val name: String,
        val executable: String,
        val taskId: String,
        val arguments: List<String> = emptyList(),
    ) : LaunchAtLoginTarget

    data class FlatpakBackground(
        val command: List<String>,
        val reason: String,
    ) : LaunchAtLoginTarget
}

/** Policy-managed states are read-only. UNKNOWN means no supported effective-state query exists. */
enum class LaunchAtLoginStatus {
    ENABLED,
    DISABLED,
    REQUIRES_APPROVAL,
    DISABLED_BY_USER,
    UNAVAILABLE,
    STALE,
    UNKNOWN,
    ENABLED_BY_POLICY,
    DISABLED_BY_POLICY,
}

enum class LaunchAtLoginError {
    INVALID_ARGUMENT,
    UNAVAILABLE,
    SYSTEM,
    CANCELLED,
    DENIED,
    CONFLICT,
    INTERNAL,
}

class LaunchAtLoginException(
    val code: LaunchAtLoginError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Blocking OS operations. Call from an IO dispatcher, including [status]. */
class LaunchAtLogin(val target: LaunchAtLoginTarget) {
    companion object {
        /** Sample on the macOS main thread while dispatching the launch AppleEvent. */
        fun launchedAtLogin(): Boolean = nativeFlag(OPERATION_LOGIN_EVENT)

        /** Install before starting the macOS application event loop; observes its launch notification. */
        fun prepareLaunchDetection() {
            nativeFlag(OPERATION_PREPARE_LOGIN_DETECTION)
        }

        private fun nativeFlag(operation: Int): Boolean =
            executeNative(operation, "") == 1
    }

    fun status(): LaunchAtLoginStatus = execute(OPERATION_STATUS)

    fun setEnabled(enabled: Boolean): LaunchAtLoginStatus =
        execute(if (enabled) OPERATION_ENABLE else OPERATION_DISABLE)

    fun openSettings() {
        execute(OPERATION_SETTINGS)
    }

    private fun execute(operation: Int): LaunchAtLoginStatus {
        val result = executeNative(operation, target.encode())
        return LaunchAtLoginStatus.entries.getOrNull(result)
            ?: throw LaunchAtLoginException(LaunchAtLoginError.INTERNAL, "Unsupported native response")
    }
}

/** Returns a non-negative native result, or throws the decoded native failure. */
private fun executeNative(operation: Int, configuration: String): Int {
    val result = NativeLaunchAtLogin.execute(operation, configuration)
    if (result < 0) {
        val code = LaunchAtLoginError.entries.getOrNull(-result - 1) ?: LaunchAtLoginError.INTERNAL
        throw LaunchAtLoginException(code, NativeLaunchAtLogin.lastError() ?: code.name)
    }
    return result
}

/**
 * NUL-delimited fields. Every kind sends the same header: kind, ID, name, executable
 * and the Windows startup task ID, which is empty for other kinds. Arguments follow it.
 */
internal fun LaunchAtLoginTarget.encode(): String {
    val fields = when (this) {
        LaunchAtLoginTarget.MacOSMainApp -> listOf("macos", "", "", "", "")
        is LaunchAtLoginTarget.XdgAutostart -> listOf("xdg", id, name, executable, "") + arguments
        is LaunchAtLoginTarget.WindowsStartup -> listOf("windows", id, name, executable, taskId) + arguments
        is LaunchAtLoginTarget.FlatpakBackground -> listOf("flatpak", "", reason, "", "") + command
    }
    require(fields.size <= MAX_TARGET_FIELDS && fields.none { '\u0000' in it }) { "Invalid launch target" }
    return fields.joinToString("\u0000").also {
        require(it.length <= LAUNCH_REQUEST_MAX_LENGTH) { "Launch target is too large" }
    }
}
