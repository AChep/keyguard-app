package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import com.artemchep.jna.util.DisposableScope
import com.artemchep.jna.util.asMemory
import com.sun.jna.Pointer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
) {
    suspendCancellableCoroutine<Unit> { continuation ->
        val retainedCallback = AtomicReference<DesktopLibJna.BiometricsVerifyCallback?>()
        val scope = DisposableScope()
        val callback = object : DesktopLibJna.BiometricsVerifyCallback {
            override fun invoke(success: Boolean, error: Pointer?) {
                if (retainedCallback.getAndSet(null) == null) {
                    return
                }

                val result = if (success) {
                    Result.success(Unit)
                } else {
                    val message = error?.getString(0L) ?: "Unknown error"
                    Result.failure(RuntimeException(message))
                }

                result.fold(
                    onSuccess = continuation::resume,
                    onFailure = continuation::resumeWithException,
                )
            }
        }
        retainedCallback.set(callback)

        continuation.invokeOnCancellation {
            retainedCallback.set(null)
        }

        try {
            lib.biometricsVerify(
                title = title
                    .asMemory()
                    .let(scope::register),
                callback = callback,
            )
        } catch (e: Throwable) {
            retainedCallback.set(null)
            throw e
        } finally {
            scope.dispose()
        }
    }
}
