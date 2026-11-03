package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.sshagent.DEFAULT_CONNECT_HOST_CANDIDATES
import com.artemchep.keyguard.common.service.sshagent.mergeAndroidSshAgentCallerIdentity
import com.artemchep.keyguard.common.service.sshagent.runAndroidAgentProxySession

/**
 * Serves one Termux GPG agent helper connection over the same loopback
 * transport as the Android SSH agent bridge. The session secret from the
 * receiver authenticates the helper, so no protobuf authentication follows.
 */
internal suspend fun runGpgAgentProxyBridge(
    requestProcessor: GpgAgentRequestProcessor,
    proxyPort: Int,
    sessionId: ByteArray,
    sessionSecret: ByteArray,
    senderAppInfo: CallerIdentity? = null,
    onOversizeResponse: (size: Int) -> Unit = {},
    connectHostCandidates: List<String> = DEFAULT_CONNECT_HOST_CANDIDATES,
    packetReadTimeoutMs: Long = DEFAULT_GPG_PROXY_PACKET_READ_TIMEOUT_MS,
) {
    val rpcHandler = GpgAgentRpcHandler(
        requestProcessor = AndroidBridgeGpgAgentRequestProcessor(
            delegate = requestProcessor,
            senderAppInfo = senderAppInfo,
        ),
    )

    runAndroidAgentProxySession(
        agentName = "GPG agent",
        proxyPort = proxyPort,
        sessionId = sessionId,
        sessionSecret = sessionSecret,
        connectHostCandidates = connectHostCandidates,
        packetReadTimeoutMs = packetReadTimeoutMs,
    ) { maxPacketSize ->
        GpgAgentPacketSession(
            rpcHandler = rpcHandler,
            authenticatedByTransport = true,
            maxPacketSize = maxPacketSize,
            onOversizeResponse = onOversizeResponse,
        )
    }
}

/**
 * The helper keeps a bridge for its whole Assuan connection and retires it
 * after five minutes without a key operation (`BRIDGE_IDLE_LIMIT` in
 * androidGpgAgent). Waiting longer than that means the app never closes a
 * bridge the helper may still send a request into.
 */
internal const val DEFAULT_GPG_PROXY_PACKET_READ_TIMEOUT_MS = 6 * 60_000L

/**
 * Attributes every request to the Android app that sent the bridge broadcast.
 * The helper's own caller metadata is display-only and its authorization is
 * never trusted, exactly as for the SSH agent bridge.
 */
internal class AndroidBridgeGpgAgentRequestProcessor(
    private val delegate: GpgAgentRequestProcessor,
    private val senderAppInfo: CallerIdentity?,
) : GpgAgentRequestProcessor {
    override suspend fun listKeys(
        caller: CallerIdentity?,
    ): GpgAgentRequestProcessor.ListKeysResult = delegate.listKeys(caller.augmented())

    override suspend fun signHash(
        request: GpgAgentMessages.SignHashRequest,
    ): GpgAgentRequestProcessor.GpgAgentOperationResult<GpgAgentMessages.SignHashResponse> =
        delegate.signHash(request.copy(caller = request.caller.augmented()))

    override suspend fun decrypt(
        request: GpgAgentMessages.PkdecryptRequest,
    ): GpgAgentRequestProcessor.GpgAgentOperationResult<GpgAgentMessages.PkdecryptResponse> =
        delegate.decrypt(request.copy(caller = request.caller.augmented()))

    private fun CallerIdentity?.augmented(): CallerIdentity? = mergeAndroidSshAgentCallerIdentity(
        caller = this,
        senderAppInfo = senderAppInfo,
        replaceCallerAuthorization = true,
    )
}
