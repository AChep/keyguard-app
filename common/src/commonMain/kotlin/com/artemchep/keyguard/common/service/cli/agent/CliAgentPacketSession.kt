package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.cli.CliServiceException
import com.artemchep.keyguard.util.foundation.constantTimeEquals
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/** Transport-independent session for the app's verified, private channel to the CLI agent. */
@OptIn(ExperimentalSerializationApi::class)
class CliAgentPacketSession(
    private val handler: CliAgentRequestHandler,
    authToken: ByteArray,
) {
    private val expectedToken = authToken.copyOf()
    private var authenticated = false
    private var closed = false

    init { require(authToken.size == AUTH_TOKEN_BYTES) }

    suspend fun process(packet: ByteArray): CliAgentPacketReply = try {
        processInternal(packet)
    } catch (e: CancellationException) {
        close()
        throw e
    } catch (_: Exception) {
        close()
        throw CliServiceException("invalid_request")
    }

    private suspend fun processInternal(packet: ByteArray): CliAgentPacketReply {
        check(!closed)
        require(packet.size in 1..MAX_PACKET_SIZE)
        val request = ProtoBuf.decodeFromByteArray<CliAgentMessages.Request>(packet).request
        val response: CliAgentMessages.Response.Kind
        if (!authenticated) {
            val supplied = (request as? CliAgentMessages.Request.Kind.Authenticate)?.value
            authenticated = try {
                supplied != null &&
                    supplied.protocolRevision == CliAgentMessages.PROTOCOL_REVISION &&
                    expectedToken.constantTimeEquals(supplied.token)
            } finally { supplied?.token?.fill(0) }
            response = if (authenticated) {
                CliAgentMessages.Response.Kind.Authenticate(CliAgentMessages.Empty)
            } else {
                cliAgentError("unauthorized")
            }
            if (!authenticated) close()
        } else {
            try {
                response = handler.handle(request)
            } finally {
                request.wipe()
            }
        }
        val encoded = try {
            ProtoBuf.encodeToByteArray(CliAgentMessages.Response(response))
        } finally {
            response.wipe()
        }
        require(encoded.size <= MAX_PACKET_SIZE)
        return CliAgentPacketReply(encoded, closed)
    }

    fun close() {
        closed = true
        authenticated = false
        expectedToken.fill(0)
    }

    companion object {
        private const val AUTH_TOKEN_BYTES = 32
        const val MAX_PACKET_SIZE = 1024 * 1024
    }
}

class CliAgentPacketReply(val packet: ByteArray, val close: Boolean)

// Every case is listed, so that a new one decides whether it holds values.

/** Zeroes the values that a request of the agent can hold. */
private fun CliAgentMessages.Request.Kind.wipe() {
    when (this) {
        is CliAgentMessages.Request.Kind.Manage -> value.variables.forEach { it.value?.fill(0) }
        is CliAgentMessages.Request.Kind.Cli -> value.request.fill(0)
        is CliAgentMessages.Request.Kind.Authenticate -> value.token.fill(0)
        is CliAgentMessages.Request.Kind.Mounts,
        is CliAgentMessages.Request.Kind.WatchMounts,
        is CliAgentMessages.Request.Kind.MountStatus,
        is CliAgentMessages.Request.Kind.ResolveMount,
        -> Unit
    }
}

/** Zeroes the values and directory grants that an answer to the agent can hold. */
private fun CliAgentMessages.Response.Kind.wipe() {
    when (this) {
        is CliAgentMessages.Response.Kind.Mounts -> value.mounts.forEach { it.bookmark?.fill(0) }
        is CliAgentMessages.Response.Kind.ResolveMount -> value.content.fill(0)
        is CliAgentMessages.Response.Kind.Cli -> value.result.fill(0)
        is CliAgentMessages.Response.Kind.Authenticate,
        is CliAgentMessages.Response.Kind.Manage,
        is CliAgentMessages.Response.Kind.MountStatus,
        is CliAgentMessages.Response.Kind.Error,
        -> Unit
    }
}
