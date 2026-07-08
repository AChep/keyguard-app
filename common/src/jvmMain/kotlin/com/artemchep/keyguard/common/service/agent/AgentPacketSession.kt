package com.artemchep.keyguard.common.service.agent

internal suspend fun <Req, Res> runAgentPacketSession(
    channel: AgentPacketChannel,
    initialAuthenticated: Boolean,
    allowAuthenticate: Boolean,
    decodeRequest: (ByteArray) -> Req,
    encodeResponse: (Res) -> ByteArray,
    isAuthenticateRequest: (Req) -> Boolean,
    isAuthenticateSuccess: (Res) -> Boolean,
    handleRequest: suspend (request: Req, authenticated: Boolean) -> Res,
    onRequest: suspend (Req, ByteArray) -> Unit = { _, _ -> },
    onResponse: suspend (Res, ByteArray) -> Unit = { _, _ -> },
) {
    var authenticated = initialAuthenticated

    while (true) {
        val requestPacket = channel.readPacket()
            ?: break
        val request = decodeRequest(requestPacket)
        onRequest(request, requestPacket)

        val response = handleRequest(request, authenticated)
        val responsePacket = encodeResponse(response)
        onResponse(response, responsePacket)
        channel.writePacket(responsePacket)

        if (allowAuthenticate && isAuthenticateRequest(request)) {
            if (isAuthenticateSuccess(response)) {
                authenticated = true
            } else {
                break
            }
        }
    }
}
