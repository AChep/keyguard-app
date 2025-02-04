package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import com.artemchep.jna.util.asMemory
import com.artemchep.jna.withDesktopLib
import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

public suspend fun biometricsIsSupported(): Boolean = withDesktopLib { lib ->
    lib.biometricsIsSupported()
}

public suspend fun biometricsVerify(
    title: String,
) {
    fun requestDeviceOwnerAuthenticationWithBiometrics(
        continuation: Continuation<Unit>,
    ) = withDesktopLib { lib ->
        val pointer = object : DesktopLibJna.BiometricsVerifyCallback {
            override fun invoke(success: Boolean, error: Pointer?) {
                if (success) {
                    continuation.resume(Unit)
                } else {
                    val m = error?.getString(0L)
                        ?: "Unknown error"
                    val e = RuntimeException(m)
                    continuation.resumeWithException(e)
                }
            }
        }

        lib.biometricsVerify(
            title = title
                .asMemory()
                .let(::register),
            callback = pointer,
        )
    }

    withContext(Dispatchers.IO) {
        suspendCoroutine { cont ->
            requestDeviceOwnerAuthenticationWithBiometrics(cont)
        }
    }
}
