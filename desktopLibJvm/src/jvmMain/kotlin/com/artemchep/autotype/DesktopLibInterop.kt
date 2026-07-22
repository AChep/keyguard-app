package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import com.artemchep.jna.util.DisposableScope
import com.artemchep.jna.util.asMemory
import com.sun.jna.Pointer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class BiometricsCallbackRetention {
    private val callbacks = ConcurrentHashMap.newKeySet<DesktopLibJna.BiometricsVerifyCallback>()

    internal val size: Int
        get() = callbacks.size

    internal fun retain(callback: DesktopLibJna.BiometricsVerifyCallback) {
        check(callbacks.add(callback))
    }

    internal fun release(callback: DesktopLibJna.BiometricsVerifyCallback): Boolean =
        callbacks.remove(callback)
}

private val biometricsCallbackRetention = BiometricsCallbackRetention()

internal fun DisposableScope.autoTypeOrThrow(
    lib: DesktopLibJna,
    payload: String,
) {
    val success = lib.autoType(
        payload = payload
            .asMemory()
            .let(::register),
    )
    check(success) {
        "Failed to auto type payload."
    }
}

internal fun getSystemAccentColorOrDefault(
    lib: DesktopLibJna,
): Int = runCatching {
    lib.getSystemAccentColor()
}.getOrDefault(0)

internal fun DisposableScope.keychainAddPasswordOrThrow(
    lib: DesktopLibJna,
    id: String,
    password: String,
) {
    val success = lib.keychainAddPassword(
        id = id
            .asMemory()
            .let(::register),
        password = password
            .asMemory()
            .let(::register),
    )
    check(success) {
        "Failed to store password in the keychain."
    }
}

internal fun DisposableScope.keychainGetPasswordOrThrow(
    lib: DesktopLibJna,
    id: String,
): String {
    val result = lib.keychainGetPassword(
        id = id
            .asMemory()
            .let(::register),
    ) ?: error("Failed to read password from the keychain.")

    return try {
        result.getString(0L)
    } finally {
        lib.freePointer(result)
    }
}

internal suspend fun biometricsVerifyOrThrow(
    lib: DesktopLibJna,
    title: String,
    callbackRetention: BiometricsCallbackRetention = biometricsCallbackRetention,
) {
    suspendCancellableCoroutine<Unit> { continuation ->
        val scope = DisposableScope()
        val callback = object : DesktopLibJna.BiometricsVerifyCallback {
            override fun invoke(success: Boolean, error: Pointer?) {
                if (!callbackRetention.release(this)) {
                    return
                }

                if (!continuation.isActive) {
                    return
                }

                if (success) {
                    continuation.resume(Unit)
                } else {
                    val message = error?.getString(0L) ?: "Unknown error"
                    continuation.resumeWithException(RuntimeException(message))
                }
            }
        }
        callbackRetention.retain(callback)

        try {
            lib.biometricsVerify(
                title = title
                    .asMemory()
                    .let(scope::register),
                callback = callback,
            )
        } catch (e: Throwable) {
            if (callbackRetention.release(callback) && continuation.isActive) {
                continuation.resumeWithException(e)
            }
        } finally {
            scope.dispose()
        }
    }
}
