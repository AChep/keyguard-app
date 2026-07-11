package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentPacketChannel
import com.artemchep.keyguard.common.service.agent.runAgentPacketSession

internal suspend fun runSshAgentPacketSession(
    channel: AgentPacketChannel,
    rpcHandler: SshAgentRpcHandler,
    initialContext: SshAgentRpcRequestContext,
    codec: SshAgentProtoCodec = SshAgentProtoCodec,
    onRequest: suspend (SshAgentMessages.IpcRequest, ByteArray) -> Unit = { _, _ -> },
    onResponse: suspend (SshAgentMessages.IpcResponse, ByteArray) -> Unit = { _, _ -> },
    readPacket: suspend (AgentPacketChannel) -> ByteArray? = { it.readPacket() },
    writePacket: suspend (AgentPacketChannel, ByteArray) -> Unit = { channel, packet ->
        channel.writePacket(packet)
    },
) {
    runAgentPacketSession(
        channel = channel,
        initialAuthenticated = initialContext.authenticated,
        allowAuthenticate = initialContext.allowAuthenticate,
        decodeRequest = codec::decodeRequest,
        encodeResponse = codec::encodeResponse,
        isAuthenticateRequest = { it.authenticate != null },
        isAuthenticateSuccess = { it.authenticate?.success == true },
        handleRequest = { request, authenticated ->
            rpcHandler.processRequest(
                request = request,
                context = initialContext.copy(authenticated = authenticated),
            )
        },
        onRequest = onRequest,
        onResponse = onResponse,
        readPacket = readPacket,
        writePacket = writePacket,
    )
}
