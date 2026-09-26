@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.instance

import com.artemchep.keyguard.util.instance.ffi.keyguard_instance_abi_version
import com.artemchep.keyguard.util.instance.ffi.keyguard_instance_acquire_or_activate
import com.artemchep.keyguard.util.instance.ffi.keyguard_instance_clear_error
import com.artemchep.keyguard.util.instance.ffi.keyguard_instance_close
import com.artemchep.keyguard.util.instance.ffi.keyguard_instance_last_error
import com.artemchep.keyguard.util.instance.ffi.keyguard_instance_stop
import com.artemchep.keyguard.util.instance.ffi.keyguard_instance_wait_event
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlin.text.CharacterCodingException

private const val MAX_ARGUMENT_LENGTH = 65_536

internal actual object NativeInstance {
    actual fun lastError(): String? {
        // Native diagnostics contain only fixed labels and numeric codes. Read on the
        // calling thread before any subsequent bridge operation can replace them.
        val bytes = ByteArray(512)
        val size = bytes.usePinned { pinned ->
            keyguard_instance_last_error(pinned.addressOf(0).reinterpret(), bytes.size.toULong())
        }
        return if (size > 0uL && size <= bytes.size.toULong()) {
            bytes.decodeToString(endIndex = size.toInt())
        } else {
            null
        }
    }

    actual fun acquireOrActivate(
        coordinationDirectory: String,
        runtimeDirectory: String,
        identity: String,
        timeoutMillis: Long,
    ): Long {
        ensureCompatibleAbi()
        if (timeoutMillis < 0L) return -1L
        return coordinationDirectory.withUtf8 { coordination, coordinationSize ->
            runtimeDirectory.withUtf8 { runtime, runtimeSize ->
                identity.withUtf8 { identityPointer, identitySize ->
                    keyguard_instance_acquire_or_activate(
                        coordination,
                        coordinationSize,
                        runtime,
                        runtimeSize,
                        identityPointer,
                        identitySize,
                        timeoutMillis.toULong(),
                    )
                }
            }
        }
    }

    actual fun waitEvent(handle: Long): Long {
        ensureCompatibleAbi()
        return if (handle < 0L) -1L else keyguard_instance_wait_event(handle.toULong())
    }

    actual fun stop(handle: Long): Long {
        ensureCompatibleAbi()
        return if (handle < 0L) -1L else keyguard_instance_stop(handle.toULong())
    }

    actual fun close(handle: Long): Long {
        ensureCompatibleAbi()
        return if (handle < 0L) -1L else keyguard_instance_close(handle.toULong())
    }

    private fun ensureCompatibleAbi() {
        keyguard_instance_clear_error()
        if (keyguard_instance_abi_version().toInt() != INSTANCE_ABI_VERSION) {
            throw InstanceException(InstanceFailureKind.PROTOCOL)
        }
    }

    private inline fun String.withUtf8(
        block: (CPointer<UByteVar>?, ULong) -> Long,
    ): Long {
        if (length > MAX_ARGUMENT_LENGTH) return -1L
        val bytes = try {
            encodeToByteArray(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
        return when {
            bytes == null -> -1L
            bytes.isEmpty() -> block(null, 0uL)
            else -> bytes.usePinned { pinned ->
                block(pinned.addressOf(0).reinterpret(), bytes.size.toULong())
            }
        }
    }
}
