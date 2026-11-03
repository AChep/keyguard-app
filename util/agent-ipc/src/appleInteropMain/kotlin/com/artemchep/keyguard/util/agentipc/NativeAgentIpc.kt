@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.agentipc

import com.artemchep.keyguard.util.agent_ipc.ffi.keyguard_agent_ipc_abi_version
import com.artemchep.keyguard.util.agent_ipc.ffi.keyguard_agent_ipc_accept
import com.artemchep.keyguard.util.agent_ipc.ffi.keyguard_agent_ipc_await_input
import com.artemchep.keyguard.util.agent_ipc.ffi.keyguard_agent_ipc_clear_error
import com.artemchep.keyguard.util.agent_ipc.ffi.keyguard_agent_ipc_close
import com.artemchep.keyguard.util.agent_ipc.ffi.keyguard_agent_ipc_close_listener
import com.artemchep.keyguard.util.agent_ipc.ffi.keyguard_agent_ipc_endpoint
import com.artemchep.keyguard.util.agent_ipc.ffi.keyguard_agent_ipc_last_error
import com.artemchep.keyguard.util.agent_ipc.ffi.keyguard_agent_ipc_listen
import com.artemchep.keyguard.util.agent_ipc.ffi.keyguard_agent_ipc_peer_pid
import com.artemchep.keyguard.util.agent_ipc.ffi.keyguard_agent_ipc_read
import com.artemchep.keyguard.util.agent_ipc.ffi.keyguard_agent_ipc_write
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlin.text.CharacterCodingException

private const val MAX_ARGUMENT_LENGTH = 65_536

// Endpoints are at most 107 bytes on Unix; the second attempt is defensive.
private const val ENDPOINT_BUFFER_SIZE = 512

// The static library is linked in, so it is never missing: only its ABI can differ.
// Every call except the diagnostic readers clears the diagnostic and checks the ABI
// version first, so a local rejection never returns an earlier call's diagnostic.
internal actual object NativeAgentIpc {
    actual fun abiVersion(): Int = keyguard_agent_ipc_abi_version().toInt()

    actual fun lastError(): String? {
        // Native diagnostics are ASCII labels and numeric codes, at most 128 bytes. Read
        // them on the calling thread before another bridge call can replace them.
        val bytes = ByteArray(512)
        val size = bytes.usePinned { pinned ->
            keyguard_agent_ipc_last_error(pinned.addressOf(0).reinterpret(), bytes.size.toULong())
        }
        return if (size > 0uL && size <= bytes.size.toULong()) {
            bytes.decodeToString(endIndex = size.toInt())
        } else {
            null
        }
    }

    actual fun listen(parent: String?, prefix: String, exactName: String?): Long {
        ensureCompatibleAbi()
        return parent.orEmpty().withUtf8 { parentPointer, parentSize ->
            prefix.withUtf8 { prefixPointer, prefixSize ->
                exactName.orEmpty().withUtf8 { exactPointer, exactSize ->
                    keyguard_agent_ipc_listen(
                        parentPointer,
                        parentSize,
                        prefixPointer,
                        prefixSize,
                        exactPointer,
                        exactSize,
                    )
                }
            }
        }
    }

    actual fun endpoint(listener: Long): String? {
        ensureCompatibleAbi()
        if (listener < 0L) return null
        val bytes = ByteArray(ENDPOINT_BUFFER_SIZE)
        val required = copyEndpoint(listener, bytes)
        return when {
            required < 0L -> null
            required <= bytes.size -> bytes.decodeToString(endIndex = required.toInt())
            required > Int.MAX_VALUE -> null
            else -> {
                val exact = ByteArray(required.toInt())
                if (copyEndpoint(listener, exact) == required) exact.decodeToString() else null
            }
        }
    }

    actual fun accept(listener: Long): Long {
        ensureCompatibleAbi()
        return if (listener < 0L) -1L else keyguard_agent_ipc_accept(listener.toULong())
    }

    actual fun closeListener(listener: Long): Long {
        ensureCompatibleAbi()
        return if (listener < 0L) -1L else keyguard_agent_ipc_close_listener(listener.toULong())
    }

    actual fun peerPid(connection: Long): Long {
        ensureCompatibleAbi()
        return if (connection < 0L) -1L else keyguard_agent_ipc_peer_pid(connection.toULong())
    }

    actual fun read(connection: Long, buffer: ByteArray, offset: Int, length: Int): Long {
        ensureCompatibleAbi()
        return when {
            connection < 0L || !buffer.hasRange(offset, length) -> -1L
            // Passed on, so the native side rejects the empty buffer with its diagnostic.
            length == 0 -> keyguard_agent_ipc_read(connection.toULong(), null, 0uL)
            // Pinned only for the call; the array is never retained.
            else -> buffer.usePinned { pinned ->
                keyguard_agent_ipc_read(
                    connection.toULong(),
                    pinned.addressOf(offset).reinterpret(),
                    length.toULong(),
                )
            }
        }
    }

    actual fun write(connection: Long, buffer: ByteArray, offset: Int, length: Int): Long {
        ensureCompatibleAbi()
        return when {
            connection < 0L || !buffer.hasRange(offset, length) -> -1L
            length == 0 -> keyguard_agent_ipc_write(connection.toULong(), null, 0uL)
            // Pinned only for the call; the array is never retained.
            else -> buffer.usePinned { pinned ->
                keyguard_agent_ipc_write(
                    connection.toULong(),
                    pinned.addressOf(offset).reinterpret(),
                    length.toULong(),
                )
            }
        }
    }

    actual fun awaitInput(connection: Long): Long {
        ensureCompatibleAbi()
        return if (connection < 0L) -1L else keyguard_agent_ipc_await_input(connection.toULong())
    }

    actual fun close(connection: Long): Long {
        ensureCompatibleAbi()
        return if (connection < 0L) -1L else keyguard_agent_ipc_close(connection.toULong())
    }
}

private fun ensureCompatibleAbi() {
    keyguard_agent_ipc_clear_error()
    if (keyguard_agent_ipc_abi_version().toInt() != AGENT_IPC_ABI_VERSION) {
        throw AgentIpcException(AgentIpcFailureKind.UNAVAILABLE)
    }
}

private fun copyEndpoint(listener: Long, bytes: ByteArray): Long = bytes.usePinned { pinned ->
    keyguard_agent_ipc_endpoint(
        listener.toULong(),
        pinned.addressOf(0).reinterpret(),
        bytes.size.toULong(),
    )
}

private fun ByteArray.hasRange(offset: Int, length: Int): Boolean =
    offset >= 0 && length >= 0 && offset <= size - length

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
