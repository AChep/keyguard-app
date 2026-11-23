package com.artemchep.keyguard.util.launchatlogin

import com.artemchep.keyguard.util.ffi.JniLibrary

internal actual object NativeLaunchAtLogin {
    private val library = JniLibrary(
        name = "keyguard_launch_at_login_jni",
        pathProperty = "keyguard.nativeLaunchAtLogin.libraryPath",
        unavailable = { cause ->
            LaunchAtLoginException(LaunchAtLoginError.UNAVAILABLE, "Native library unavailable", cause)
        },
        verify = {
            if (NativeLaunchAtLoginJni.abiVersion() != 1) {
                throw LaunchAtLoginException(LaunchAtLoginError.UNAVAILABLE, "Unsupported launch-at-login ABI")
            }
        },
    )

    actual fun execute(operation: Int, configuration: String): Int {
        library.ensureLoaded()
        return NativeLaunchAtLoginJni.execute(operation, configuration)
    }

    actual fun lastError(): String? = NativeLaunchAtLoginJni.lastError()
}
