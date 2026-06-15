package com.artemchep.keyguard.common.service.sshagent

internal suspend fun runSshAgentPacketSession(
    channel: SshAgentPacketChannel,
    rpcHandler: SshAgentRpcHandler,
    initialContext: SshAgentRpcRequestContext,
    codec: SshAgentProtoCodec = SshAgentProtoCodec,
    onRequest: suspend (SshAgentMessages.IpcRequest, ByteArray) -> Unit = { _, _ -> },
    onResponse: suspend (SshAgentMessages.IpcResponse, ByteArray) -> Unit = { _, _ -> },
) {
    var authenticated = initialContext.authenticated

    while (true) {
        val requestPacket = channel.readPacket()
            ?: break
        val request = codec.decodeRequest(requestPacket)
        onRequest(request, requestPacket)

        val response = rpcHandler.processRequest(
            request = request,
            context = initialContext.copy(authenticated = authenticated),
        )
        val responsePacket = codec.encodeResponse(response)
        onResponse(response, responsePacket)
        channel.writePacket(responsePacket)

        if (initialContext.allowAuthenticate && request.authenticate != null) {
            if (response.authenticate?.success == true) {
                authenticated = true
            } else {
                break
            }
        }
    }
}
