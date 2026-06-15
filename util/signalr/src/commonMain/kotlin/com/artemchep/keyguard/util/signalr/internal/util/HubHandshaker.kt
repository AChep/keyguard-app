package com.artemchep.keyguard.util.signalr.internal.util

import com.artemchep.keyguard.util.signalr.HubProtocol
import com.artemchep.keyguard.util.signalr.internal.RECORD_SEPARATOR
import com.artemchep.keyguard.util.signalr.internal.Transport
import com.artemchep.keyguard.util.signalr.internal.model.Handshake
import com.artemchep.keyguard.util.signalr.internal.model.HandshakeResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Duration

internal suspend fun handshake(
    protocol: HubProtocol,
    handshakeResponseTimeout: Duration,
    transport: Transport,
    json: Json,
): ByteArray? {
    val handshake = json.encodeToString(
        Handshake(
            protocol = protocol.name,
            version = protocol.version,
        ),
    ) + RECORD_SEPARATOR
    transport.sendText(handshake)

    val handshakePayload = withTimeout(handshakeResponseTimeout) {
        transport.receive().first()
    }

    val recordSeparatorIndex = handshakePayload.indexOf(RECORD_SEPARATOR.code.toByte())
    if (recordSeparatorIndex < 0) {
        throw RuntimeException("HubMessage is incomplete.")
    }

    val handshakeCandidate = handshakePayload.decodeToString(
        startIndex = 0,
        endIndex = recordSeparatorIndex,
    )
    val response = try {
        val responseElement = json.decodeFromString<JsonObject>(handshakeCandidate)
        if ("type" in responseElement) {
            throw RuntimeException("Expected a handshake response from the server.")
        }

        json.decodeFromJsonElement<HandshakeResponse>(responseElement)
    } catch (ex: SerializationException) {
        throw RuntimeException("An invalid handshake response was received from the server.", ex)
    }

    if (response.error != null) {
        throw RuntimeException("Error in handshake ${response.error}")
    }

    return handshakePayload
        .copyOfRange(recordSeparatorIndex + 1, handshakePayload.size)
        .takeIf { it.isNotEmpty() }
}
