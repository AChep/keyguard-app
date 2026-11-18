package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.AgentIpcAuthentication
import com.artemchep.keyguard.common.service.agent.AgentIpcAuthenticator
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.AgentPacketProtocol
import com.artemchep.keyguard.common.service.agent.AgentPacketReply
import com.artemchep.keyguard.common.service.agent.AgentPacketSession
import com.artemchep.keyguard.common.service.cli.CliServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Transport-independent session for the app's verified, private channel to the
 * CLI agent: one per connection. A reply may turn its connection into a
 * stream, see [AgentPacketReply.upgrade].
 */
class CliAgentPacketSession(
    handler: CliAgentRequestHandler,
    authToken: ByteArray,
) {
    private val session = AgentPacketSession(CliAgentPacketProtocol(handler, AgentIpcAuthenticator(authToken)))

    /** Its errors never hold the packet: any but cancellation is `invalid_request`. */
    suspend fun process(packet: ByteArray): AgentPacketReply = try {
        session.process(packet)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw CliServiceException("invalid_request")
    }

    fun close() = session.close()

    companion object {
        const val MAX_PACKET_SIZE = 1024 * 1024
    }
}

/**
 * The first request must authenticate. A rejected agent learns the app's
 * revision, then the connection closes.
 */
@OptIn(ExperimentalSerializationApi::class)
private class CliAgentPacketProtocol(
    private val handler: CliAgentRequestHandler,
    private val authenticator: AgentIpcAuthenticator,
) : AgentPacketProtocol<CliAgentMessages.Request.Kind, CliAgentAnswer> {
    override val maxPacketSize: Int get() = CliAgentPacketSession.MAX_PACKET_SIZE

    override fun decode(packet: ByteArray) = ProtoBuf.decodeFromByteArray<CliAgentMessages.Request>(packet).request

    override fun encode(response: CliAgentAnswer) =
        ProtoBuf.encodeToByteArray(CliAgentMessages.Response(response.response))

    override suspend fun handle(
        request: CliAgentMessages.Request.Kind,
        authenticated: Boolean,
    ): CliAgentAnswer {
        if (authenticated) return handler.handle(request)
        val supplied = (request as? CliAgentMessages.Request.Kind.Authenticate)?.value
        val success = supplied != null && authenticator.check(supplied) == AgentIpcAuthentication.ACCEPTED
        val response = CliAgentMessages.Response.Kind.Authenticate(
            AgentIpcMessages.AuthenticateResponse(
                success = success,
                protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
            ),
        )
        return CliAgentAnswer(response)
    }

    override fun authenticatedAfter(
        request: CliAgentMessages.Request.Kind,
        response: CliAgentAnswer,
        authenticated: Boolean,
    ): Boolean? = if (authenticated) {
        null
    } else {
        (response.response as? CliAgentMessages.Response.Kind.Authenticate)?.value?.success == true
    }

    override fun wipeRequest(request: CliAgentMessages.Request.Kind) = request.wipe()

    override fun wipeResponse(response: CliAgentAnswer) = response.response.wipe()

    override fun upgrade(response: CliAgentAnswer) = response.upgrade

    override fun close() = authenticator.close()
}

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
        is CliAgentMessages.Request.Kind.BrowserOpen,
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
        is CliAgentMessages.Response.Kind.BrowserOpen,
        is CliAgentMessages.Response.Kind.Error,
        -> Unit
    }
}
