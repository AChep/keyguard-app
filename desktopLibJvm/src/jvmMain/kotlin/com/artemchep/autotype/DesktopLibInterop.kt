package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import com.artemchep.jna.util.DisposableScope
import com.artemchep.jna.util.asMemory
import com.sun.jna.Pointer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val NATIVE_FALSE = 0
private const val NATIVE_TRUE = 1

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
    windowHandle: Long,
    title: String,
    callbackRetention: BiometricsCallbackRetention = biometricsCallbackRetention,
) {
    suspendCancellableCoroutine<Unit> { continuation ->
        val scope = DisposableScope()
        val callback = object : DesktopLibJna.BiometricsVerifyCallback {
            override fun invoke(status: Int, error: Pointer?) {
                if (!callbackRetention.release(this)) {
                    return
                }

                if (!continuation.isActive) {
                    return
                }

                when (val nativeStatus = BiometricsStatus.fromCode(status)) {
                    BiometricsStatus.SUCCESS -> {
                        continuation.resume(Unit)
                    }
                    else -> {
                        val exception = BiometricsException(
                            status = nativeStatus,
                            message = error?.getString(0L) ?: "Biometric verification failed.",
                        )
                        continuation.resumeWithException(exception)
                    }
                }
            }
        }
        callbackRetention.retain(callback)

        try {
            lib.biometricsVerify(
                windowHandle = windowHandle,
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

internal fun DisposableScope.biometricsTransformSecretOrThrow(
    lib: DesktopLibJna,
    windowHandle: Long,
    title: String,
    input: ByteArray,
    decrypt: Boolean,
): ByteArray {
    require(input.isNotEmpty()) {
        "Input must not be empty."
    }

    var outcome: Result<ByteArray>? = null
    val callback = object : DesktopLibJna.BiometricsResultCallback {
        override fun invoke(
            status: Int,
            result: Pointer?,
            resultLength: Long,
            error: Pointer?,
        ) {
            val nativeStatus = BiometricsStatus.fromCode(status)
            outcome = if (nativeStatus == BiometricsStatus.SUCCESS) {
                val length = resultLength.toInt()
                if (result == null || length <= 0 || length.toLong() != resultLength) {
                    Result.failure(IllegalStateException("Native library returned an invalid result."))
                } else {
                    Result.success(result.getByteArray(0L, length))
                }
            } else {
                Result.failure(
                    BiometricsException(
                        status = nativeStatus,
                        message = error?.getString(0L) ?: "Biometric verification failed.",
                    ),
                )
            }
        }
    }
    val success = lib.biometricsTransformSecret(
        windowHandle = windowHandle,
        title = title
            .asMemory()
            .let(::register),
        input = input
            .asMemory()
            .let(::register),
        inputLength = input.size.toLong(),
        decrypt = if (decrypt) NATIVE_TRUE else NATIVE_FALSE,
        callback = callback,
    )
    check(success != NATIVE_FALSE) {
        "Failed to invoke the biometric secret transform."
    }
    return checkNotNull(outcome) {
        "Biometric secret transform did not complete synchronously."
    }.getOrThrow()
}
