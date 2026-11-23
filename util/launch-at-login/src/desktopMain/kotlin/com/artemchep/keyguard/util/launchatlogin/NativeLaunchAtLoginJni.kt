package com.artemchep.keyguard.util.launchatlogin

internal object NativeLaunchAtLoginJni {
    external fun abiVersion(): Int

    external fun execute(operation: Int, configuration: String): Int

    external fun lastError(): String?
}
