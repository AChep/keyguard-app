package com.artemchep.keyguard

import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.protobuf.ProtoBuf
import platform.posix.AF_UNIX
import platform.posix.SOCK_STREAM
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.listen
import platform.posix.read
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.unlink
import platform.posix.write

@OptIn(ExperimentalForeignApi::class)
class SshAgentIpcServerApple(
    private val authToken: ByteArray,
    private val processor: SshAgentRequestProcessor,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private companion object {
        const val MAX_PACKET_SIZE = 16 * 1024 * 1024
    }

    @Volatile
    private var serverFd: Int = -1

    fun stop() {
        val fd = serverFd
        serverFd = -1
        if (fd >= 0) close(fd)
    }

    /** Binds [socketPath] and serves connections until the scope is cancelled. */
    suspend fun start(socketPath: String) {
        unlink(socketPath)
        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        if (fd < 0) {
            log("Failed to create socket")
            return
        }
        serverFd = fd
        // Build sockaddr_un as a raw byte buffer (the struct isn't exposed in
        // platform.posix on this target): darwin layout is
        // [sun_len: u8][sun_family: u8][sun_path: char[104]].
        val pathBytes = socketPath.encodeToByteArray()
        require(pathBytes.size < UNIX_SOCKET_PATH_CAPACITY) { "Socket path too long: $socketPath" }
        val addrLen = 2 + pathBytes.size + 1
        val addrBytes = ByteArray(106)
        addrBytes[0] = addrLen.toByte()
        addrBytes[1] = AF_UNIX.toByte()
        pathBytes.copyInto(addrBytes, destinationOffset = 2)
        val bound = addrBytes.usePinned { pinned ->
            bind(fd, pinned.addressOf(0).reinterpret<sockaddr>(), addrLen.convert())
        }
        if (bound != 0) {
            log("Failed to bind socket at $socketPath")
            stop()
            return
        }
        if (listen(fd, SOCKET_BACKLOG) != 0) {
            log("Failed to listen on socket")
            stop()
            return
        }
        log("IPC server listening on $socketPath")

        while (scope.isActive && serverFd >= 0) {
            val client = accept(fd, null, null)
            if (client < 0) break
            scope.launch(Dispatchers.Default) {
                try {
                    handleConnection(client)
                } finally {
                    close(client)
                }
            }
        }
        stop()
        unlink(socketPath)
    }

    private suspend fun handleConnection(client: Int) {
        var authenticated = false
        while (scope.isActive) {
            val packet = readPacket(client) ?: break
            val request = runCatching {
                ProtoBuf.decodeFromByteArray(SshAgentMessages.IpcRequest.serializer(), packet)
            }.getOrNull() ?: break

            val response = dispatch(request, authenticated)
            if (request.authenticate != null && response.authenticate?.success == true) {
                authenticated = true
            }
            val out = ProtoBuf.encodeToByteArray(SshAgentMessages.IpcResponse.serializer(), response)
            if (!writePacket(client, out)) break
        }
    }

    private suspend fun dispatch(
        request: SshAgentMessages.IpcRequest,
        authenticated: Boolean,
    ): SshAgentMessages.IpcResponse {
        request.authenticate?.let { auth ->
            val ok = auth.protocolRevision == SshAgentMessages.PROTOCOL_REVISION &&
                auth.token.contentEquals(authToken)
            return SshAgentMessages.IpcResponse(
                id = request.id,
                authenticate = SshAgentMessages.AuthenticateResponse(
                    success = ok,
                    protocolRevision = SshAgentMessages.PROTOCOL_REVISION,
                ),
            )
        }
        if (!authenticated) {
            return SshAgentMessages.IpcResponse(
                id = request.id,
                error = SshAgentMessages.ErrorResponse(
                    message = "Not authenticated",
                    code = SshAgentMessages.ErrorCode.NOT_AUTHENTICATED,
                ),
            )
        }
        request.listKeys?.let { req ->
            return when (val result = processor.listKeys(req.caller)) {
                is SshAgentRequestProcessor.ListKeysResult.Success ->
                    SshAgentMessages.IpcResponse(id = request.id, listKeys = result.response)

                SshAgentRequestProcessor.ListKeysResult.VaultLocked ->
                    errorResponse(request.id, "Vault locked", SshAgentMessages.ErrorCode.VAULT_LOCKED)
            }
        }
        request.signData?.let { req ->
            return when (val result = processor.signData(req)) {
                is SshAgentRequestProcessor.SignDataResult.Success ->
                    SshAgentMessages.IpcResponse(id = request.id, signData = result.response)

                SshAgentRequestProcessor.SignDataResult.VaultLocked ->
                    errorResponse(request.id, "Vault locked", SshAgentMessages.ErrorCode.VAULT_LOCKED)

                SshAgentRequestProcessor.SignDataResult.UserDenied ->
                    errorResponse(request.id, "User denied", SshAgentMessages.ErrorCode.USER_DENIED)

                SshAgentRequestProcessor.SignDataResult.KeyNotFound ->
                    errorResponse(request.id, "Key not found", SshAgentMessages.ErrorCode.KEY_NOT_FOUND)

                is SshAgentRequestProcessor.SignDataResult.Failure ->
                    errorResponse(request.id, result.message, SshAgentMessages.ErrorCode.UNSPECIFIED)
            }
        }
        return errorResponse(request.id, "Unknown request", SshAgentMessages.ErrorCode.UNSPECIFIED)
    }

    private fun errorResponse(id: Long, message: String, code: Int) = SshAgentMessages.IpcResponse(
        id = id,
        error = SshAgentMessages.ErrorResponse(message = message, code = code),
    )

    // --- framing (length-prefixed, big-endian) -------------------------------

    private fun readPacket(fd: Int): ByteArray? {
        val lenBytes = readFully(fd, 4) ?: return null
        val len = ((lenBytes[0].toInt() and 0xFF) shl 24) or
            ((lenBytes[1].toInt() and 0xFF) shl 16) or
            ((lenBytes[2].toInt() and 0xFF) shl 8) or
            (lenBytes[3].toInt() and 0xFF)
        if (len <= 0 || len > MAX_PACKET_SIZE) return null
        return readFully(fd, len)
    }

    private fun writePacket(fd: Int, payload: ByteArray): Boolean {
        if (payload.isEmpty() || payload.size > MAX_PACKET_SIZE) return false
        val header = ByteArray(4)
        header[0] = (payload.size ushr MOST_SIGNIFICANT_BYTE_SHIFT).toByte()
        header[1] = (payload.size ushr SECOND_BYTE_SHIFT).toByte()
        header[2] = (payload.size ushr Byte.SIZE_BITS).toByte()
        header[LAST_HEADER_BYTE_INDEX] = payload.size.toByte()
        return writeFully(fd, header) && writeFully(fd, payload)
    }

    private fun readFully(fd: Int, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var offset = 0
        buf.usePinned { pinned ->
            while (offset < n) {
                val r = read(fd, pinned.addressOf(offset), (n - offset).convert()).toLong()
                if (r <= 0L) return null
                offset += r.toInt()
            }
        }
        return buf
    }

    private fun writeFully(fd: Int, data: ByteArray): Boolean {
        var offset = 0
        data.usePinned { pinned ->
            while (offset < data.size) {
                val w = write(fd, pinned.addressOf(offset), (data.size - offset).convert()).toLong()
                if (w <= 0L) return false
                offset += w.toInt()
            }
        }
        return true
    }
}

private const val UNIX_SOCKET_PATH_CAPACITY = 104

private const val SOCKET_BACKLOG = 8

private const val MOST_SIGNIFICANT_BYTE_SHIFT = 24

private const val SECOND_BYTE_SHIFT = 16

private const val LAST_HEADER_BYTE_INDEX = 3
