package com.artemchep.keyguard.util.launchatlogin

internal expect object NativeLaunchAtLogin {
    fun execute(operation: Int, configuration: String): Int

    fun lastError(): String?
}
