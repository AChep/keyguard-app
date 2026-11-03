package com.artemchep.keyguard.feature.auth.companion

import com.artemchep.keyguard.android.CompanionAuthActivity
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The phone side of companion auth: answers a watch's login request and sends
 * the result back to it.
 */
internal class CompanionAuthReceiverAndroid(
    private val json: Json,
    private val transport: CompanionAuthTransportAndroid,
    private val security: CompanionAuthSecurityAndroid,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val receiverSessionMutex = Mutex()

    suspend fun getLaunchRequest(
        requestId: String,
        provider: CompanionAuthProvider,
    ): CompanionAuthActivity.Request? {
        val normalizedRequestId = canonicalCompanionAuthRequestIdOrNull(requestId)
            ?: return null
        val session = security.getPendingSession(requestId)
            ?: return null
        if (session.role != CompanionAuthPendingSession.Role.Receiver) {
            return null
        }
        if (session.isLaunchExpired()) {
            security.deleteRequestArtifacts(normalizedRequestId)
            return null
        }
        if (session.provider != provider || session.isExpired() || session.launchConsumed) {
            return null
        }
        security.putPendingSession(
            session.copy(
                requestId = normalizedRequestId,
                launchConsumed = true,
            ),
        )
        return CompanionAuthActivity.Request(
            requestId = normalizedRequestId,
            provider = session.provider,
        )
    }

    fun notifyCancelledFromPhone(
        requestId: String,
        provider: CompanionAuthProvider,
        message: String? = null,
    ) {
        scope.launch {
            runCatchingNonFatal {
                val session = validateReceiverSession(
                    requestId = requestId,
                    provider = provider,
                ) ?: return@runCatchingNonFatal
                sendEncryptedResponse(
                    session = session,
                    status = CompanionAuthStatus.CANCELLED,
                    payload = CompanionAuthCancelledPayload(message = message),
                )
                security.deleteRequestArtifacts(requestId)
            }.onFailure(::recordException)
        }
    }

    fun notifyErrorFromPhone(
        requestId: String,
        provider: CompanionAuthProvider,
        error: CompanionAuthError,
        message: String? = null,
    ) {
        scope.launch {
            runCatchingNonFatal {
                val session = validateReceiverSession(
                    requestId = requestId,
                    provider = provider,
                ) ?: return@runCatchingNonFatal
                sendEncryptedResponse(
                    session = session,
                    status = CompanionAuthStatus.ERROR,
                    payload = CompanionAuthErroredPayload(
                        error = error,
                        message = message,
                    ),
                )
                security.deleteRequestArtifacts(requestId)
            }.onFailure(::recordException)
        }
    }

    suspend fun completeBitwardenOnPhone(
        requestId: String,
        payload: CompanionBitwardenPayload,
    ) {
        val session = requireReceiverSession(
            requestId = requestId,
            provider = CompanionAuthProvider.BITWARDEN,
        )
        sendEncryptedResponse(
            session = session,
            status = CompanionAuthStatus.SUCCESS,
            payload = payload,
        )
        security.deleteRequestArtifacts(requestId)
    }

    suspend fun completeKeePassOnPhone(
        requestId: String,
        payload: CompanionKeePassPayload,
        databaseUri: String,
        keyUri: String?,
    ) {
        val session = requireReceiverSession(
            requestId = requestId,
            provider = CompanionAuthProvider.KEEPASS,
        )
        transport.openOutgoingChannelAndCopy(
            nodeId = session.expectedNodeId,
            path = CompanionAuthProtocol.keepassDatabaseChannelPath(requestId),
            sourceUri = databaseUri,
        )
        keyUri?.let { uri ->
            val keyBlob = security.encryptBlob(
                session = session,
                data = transport.readFromFile(uri),
            )
            transport.openOutgoingChannelAndWrite(
                nodeId = session.expectedNodeId,
                path = CompanionAuthProtocol.keepassKeyFileChannelPath(requestId),
                payload = json.encodeToString(keyBlob).encodeToByteArray(),
            )
        }
        sendEncryptedResponse(
            session = session,
            status = CompanionAuthStatus.SUCCESS,
            payload = payload,
        )
        security.deleteRequestArtifacts(requestId)
    }

    suspend fun onPeerRequestReceived(
        nodeId: String,
        request: CompanionAuthRequest,
    ) {
        val normalizedRequestId = canonicalCompanionAuthRequestIdOrNull(request.requestId)
        if (normalizedRequestId == null) {
            sendPlainError(
                nodeId = nodeId,
                requestId = request.requestId,
                provider = request.provider,
                error = CompanionAuthError.MALFORMED_REQUEST_ID,
                message = "Companion auth request id is malformed.",
            )
            return
        }
        if (request.protocolVersion != CompanionAuthProtocol.VERSION) {
            sendPlainError(
                nodeId = nodeId,
                requestId = normalizedRequestId,
                provider = request.provider,
                error = CompanionAuthError.UNSUPPORTED_PROTOCOL,
                message = "Companion auth protocol mismatch.",
            )
            return
        }
        if (!security.isValidRemotePublicKey(request.watchPublicKey)) {
            sendPlainError(
                nodeId = nodeId,
                requestId = normalizedRequestId,
                provider = request.provider,
                error = CompanionAuthError.SECURITY_VALIDATION_FAILED,
                message = "Companion auth request key validation failed.",
            )
            return
        }

        val session = getOrCreateReceiverSession(
            normalizedRequestId = normalizedRequestId,
            nodeId = nodeId,
            request = request,
        )
        if (session == null) {
            sendPlainError(
                nodeId = nodeId,
                requestId = normalizedRequestId,
                provider = request.provider,
                error = CompanionAuthError.SECURITY_VALIDATION_FAILED,
                message = "Companion auth request validation failed.",
            )
            security.deleteRequestArtifacts(normalizedRequestId)
            return
        }

        sendPlainResponse(
            nodeId = nodeId,
            response = CompanionAuthResponse(
                requestId = normalizedRequestId,
                provider = request.provider,
                protocolVersion = CompanionAuthProtocol.VERSION,
                status = CompanionAuthStatus.STARTED,
                phonePublicKey = session.localPublicKeyBase64,
            ),
        )
    }

    private suspend fun getOrCreateReceiverSession(
        normalizedRequestId: String,
        nodeId: String,
        request: CompanionAuthRequest,
    ): CompanionAuthPendingSession? = receiverSessionMutex.withLock {
        val existingSession = security.getPendingSession(normalizedRequestId)
        when {
            existingSession == null -> {
                val localNodeId = transport.getLocalNodeId()
                val keyPair = security.createEphemeralKeyPair()
                CompanionAuthPendingSession(
                    requestId = normalizedRequestId,
                    provider = request.provider,
                    role = CompanionAuthPendingSession.Role.Receiver,
                    localNodeId = localNodeId,
                    expectedNodeId = nodeId,
                    protocolVersion = request.protocolVersion,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    localPrivateKeyBase64 = keyPair.privateKeyBase64,
                    localPublicKeyBase64 = keyPair.publicKeyBase64,
                    remotePublicKeyBase64 = request.watchPublicKey,
                ).also { pendingSession ->
                    security.putPendingSession(pendingSession)
                }
            }

            canReuseReceiverSession(
                session = existingSession,
                nodeId = nodeId,
                request = request,
            ) -> existingSession

            else -> null
        }
    }

    private suspend fun validateReceiverSession(
        requestId: String,
        provider: CompanionAuthProvider,
    ): CompanionAuthPendingSession? {
        val normalizedRequestId = canonicalCompanionAuthRequestIdOrNull(requestId)
            ?: return null
        val session = security.getPendingSession(normalizedRequestId)
            ?: return null
        return if (
            session.role == CompanionAuthPendingSession.Role.Receiver &&
            session.provider == provider &&
            session.protocolVersion == CompanionAuthProtocol.VERSION &&
            !session.remotePublicKeyBase64.isNullOrEmpty()
        ) {
            session
        } else {
            null
        }
    }

    private suspend fun requireReceiverSession(
        requestId: String,
        provider: CompanionAuthProvider,
    ): CompanionAuthPendingSession = validateReceiverSession(
        requestId = requestId,
        provider = provider,
    ) ?: throw CompanionAuthSessionUnavailableException()

    private suspend fun sendEncryptedResponse(
        session: CompanionAuthPendingSession,
        status: CompanionAuthStatus,
        payload: CompanionAuthSecurePayload,
    ) {
        sendPlainResponse(
            nodeId = session.expectedNodeId,
            response = CompanionAuthResponse(
                requestId = session.requestId,
                provider = session.provider,
                protocolVersion = CompanionAuthProtocol.VERSION,
                status = status,
                encryptedPayload = security.encryptSecurePayload(
                    session = session,
                    payload = payload,
                ),
            ),
        )
    }

    private suspend fun sendPlainError(
        nodeId: String,
        requestId: String,
        provider: CompanionAuthProvider,
        error: CompanionAuthError,
        message: String,
    ) {
        sendPlainResponse(
            nodeId = nodeId,
            response = CompanionAuthResponse(
                requestId = requestId,
                provider = provider,
                protocolVersion = CompanionAuthProtocol.VERSION,
                status = CompanionAuthStatus.ERROR,
                error = error,
                message = message,
            ),
        )
    }

    private suspend fun sendPlainResponse(
        nodeId: String,
        response: CompanionAuthResponse,
    ) {
        recordLog(
            "Companion auth sendResponse status=${response.status} provider=${response.provider} requestId=${response.requestId}",
        )
        transport.sendMessage(
            nodeId = nodeId,
            path = CompanionAuthProtocol.RESPONSE_PATH,
            payload = json.encodeToString(response).encodeToByteArray(),
        )
    }
}

internal fun canReuseReceiverSession(
    session: CompanionAuthPendingSession,
    nodeId: String,
    request: CompanionAuthRequest,
): Boolean = session.role == CompanionAuthPendingSession.Role.Receiver &&
    !session.isLaunchExpired() &&
    session.provider == request.provider &&
    session.expectedNodeId == nodeId &&
    session.remotePublicKeyBase64 == request.watchPublicKey &&
    session.protocolVersion == request.protocolVersion
