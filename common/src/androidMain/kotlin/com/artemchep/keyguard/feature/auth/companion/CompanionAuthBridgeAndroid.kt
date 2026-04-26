package com.artemchep.keyguard.feature.auth.companion

import com.artemchep.keyguard.platform.recordException
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

class CompanionAuthBridgeAndroid private constructor(
    private val json: Json,
    private val coordinator: CompanionAuthCoordinatorAndroid,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        json = directDI.instance(),
        coordinator = directDI.instance(),
    )

    fun phoneAvailabilityFlow(): Flow<Boolean> =
        coordinator.phoneAvailabilityFlow()

    fun reachablePhoneDevicesFlow(): Flow<List<CompanionAuthPhoneDevice>> =
        coordinator.reachablePhoneDevicesFlow()

    suspend fun isPhoneCompanionAvailable(): Boolean =
        phoneAvailabilityFlow().first()

    suspend fun getReachablePhoneDevices(): List<CompanionAuthPhoneDevice> =
        coordinator.getReachablePhoneDevices()

    suspend fun startOnPhone(
        provider: CompanionAuthProvider,
        nodeId: String? = null,
    ): String? = coordinator.startOnPhone(
        provider = provider,
        nodeId = nodeId,
    )

    suspend fun sweepExpiredArtifacts(
        now: Long = System.currentTimeMillis(),
    ) = coordinator.sweepExpiredArtifacts(now = now)

    suspend fun getLaunchRequest(
        requestId: String,
        provider: CompanionAuthProvider,
    ) = coordinator.getLaunchRequest(
        requestId = requestId,
        provider = provider,
    )

    fun getRequestStateFlow(
        requestId: String,
    ): Flow<CompanionAuthRequestState?> = coordinator.getRequestStateFlow(requestId)

    fun notifyCancelledFromPhone(
        requestId: String,
        provider: CompanionAuthProvider,
        message: String? = null,
    ) {
        coordinator.notifyCancelledFromPhone(
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
        coordinator.notifyErrorFromPhone(
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
        coordinator.completeBitwardenOnPhone(
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
        coordinator.completeKeePassOnPhone(
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
                coordinator.onPeerRequestReceived(
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
                coordinator.onWatchResponseReceived(
                    nodeId = messageEvent.sourceNodeId,
                    response = response,
                )
            }
        }
    }

    suspend fun onChannelOpened(
        channel: ChannelClient.Channel,
    ) {
        coordinator.onChannelOpened(channel)
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
