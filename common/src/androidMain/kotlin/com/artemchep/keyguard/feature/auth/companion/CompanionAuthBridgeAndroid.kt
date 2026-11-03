package com.artemchep.keyguard.feature.auth.companion

import com.artemchep.keyguard.platform.recordException
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class CompanionAuthBridgeAndroid internal constructor(
    private val json: Json,
    private val initiator: CompanionAuthInitiatorAndroid,
    private val receiver: CompanionAuthReceiverAndroid,
    private val security: CompanionAuthSecurityAndroid,
) {

    fun phoneDiscoveryFlow(): Flow<CompanionAuthPhoneDiscovery> =
        initiator.phoneDiscoveryFlow()

    suspend fun startOnPhone(
        provider: CompanionAuthProvider,
        nodeId: String? = null,
    ): String? = initiator.startOnPhone(
        provider = provider,
        nodeId = nodeId,
    )

    suspend fun sweepExpiredArtifacts(
        now: Long = System.currentTimeMillis(),
    ) = security.sweepExpiredArtifacts(now = now)

    suspend fun getLaunchRequest(
        requestId: String,
        provider: CompanionAuthProvider,
    ) = receiver.getLaunchRequest(
        requestId = requestId,
        provider = provider,
    )

    fun getRequestStateFlow(
        requestId: String,
    ): Flow<CompanionAuthRequestState?> = initiator.getRequestStateFlow(requestId)

    fun notifyCancelledFromPhone(
        requestId: String,
        provider: CompanionAuthProvider,
        message: String? = null,
    ) {
        receiver.notifyCancelledFromPhone(
            requestId = requestId,
            provider = provider,
            message = message,
        )
    }

    fun notifyErrorFromPhone(
        requestId: String,
        provider: CompanionAuthProvider,
        error: CompanionAuthError,
        message: String? = null,
    ) {
        receiver.notifyErrorFromPhone(
            requestId = requestId,
            provider = provider,
            error = error,
            message = message,
        )
    }

    suspend fun completeBitwardenOnPhone(
        requestId: String,
        payload: CompanionBitwardenPayload,
    ) {
        receiver.completeBitwardenOnPhone(
            requestId = requestId,
            payload = payload,
        )
    }

    suspend fun completeKeePassOnPhone(
        requestId: String,
        payload: CompanionKeePassPayload,
        databaseUri: String,
        keyUri: String?,
    ) {
        receiver.completeKeePassOnPhone(
            requestId = requestId,
            payload = payload,
            databaseUri = databaseUri,
            keyUri = keyUri,
        )
    }

    suspend fun onMessageReceived(
        messageEvent: MessageEvent,
    ) {
        when (messageEvent.path) {
            CompanionAuthProtocol.REQUEST_PATH -> {
                val request = decode<CompanionAuthRequest>(messageEvent.data) ?: return
                receiver.onPeerRequestReceived(
                    nodeId = messageEvent.sourceNodeId,
                    request = request,
                )
            }

            CompanionAuthProtocol.RESPONSE_PATH -> {
                if (messageEvent.data.size > CompanionAuthProtocol.MAX_RESPONSE_MESSAGE_BYTES) {
                    recordException(
                        CompanionAuthPayloadTooLargeException(
                            CompanionAuthProtocol.MAX_RESPONSE_MESSAGE_BYTES.toLong(),
                        ),
                    )
                    return
                }
                val response = decode<CompanionAuthResponse>(messageEvent.data) ?: return
                initiator.onWatchResponseReceived(
                    nodeId = messageEvent.sourceNodeId,
                    response = response,
                )
            }
        }
    }

    suspend fun onChannelOpened(
        channel: ChannelClient.Channel,
    ) {
        initiator.onChannelOpened(channel)
    }

    private inline fun <reified T> decode(
        data: ByteArray,
    ): T? = try {
        json.decodeFromString<T>(data.decodeToString())
    } catch (e: SerializationException) {
        recordException(e)
        null
    }
}
