package com.artemchep.keyguard.util.signalr.internal.protocols

import com.artemchep.keyguard.util.signalr.HubMessage
import com.artemchep.keyguard.util.signalr.HubProtocol
import com.artemchep.keyguard.util.signalr.logger.Logger
import com.artemchep.keyguard.util.signalr.TransferFormat
import com.artemchep.keyguard.util.signalr.internal.RECORD_SEPARATOR
import kotlinx.serialization.json.Json

internal class JsonHubProtocol(
    private val logger: Logger,
) : HubProtocol {
    private companion object {
        const val PROTOCOL_NAME = "json"
        const val PROTOCOL_VERSION = 1
    }

    override val name: String get() = PROTOCOL_NAME

    override val version: Int get() = PROTOCOL_VERSION

    override val transferFormat: TransferFormat
        get() = TransferFormat.Text

    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
        }
    }

    override fun parseMessages(
        payload: ByteArray,
    ): List<HubMessage> {
        val payloadString = payload.decodeToString(0, payload.size)
        if (payloadString.isEmpty()) {
            return emptyList()
        }
        if (payloadString.last() != RECORD_SEPARATOR) {
            throw RuntimeException("HubMessage is incomplete.")
        }

        return payloadString
            .split(RECORD_SEPARATOR)
            .filter { it.isNotEmpty() }
            .mapNotNull { str ->
                try {
                    logger.log(Logger.Severity.INFO, "Decoding message: $str", null)
                    json.decodeFromString<HubMessage>(str)
                } catch (ex: Exception) {
                    logger.log(Logger.Severity.ERROR, "Failed to decode message: $str", ex)
                    null
                }
            }
    }

    override fun writeMessage(
        message: HubMessage,
    ): ByteArray = kotlin.run {
        val text = json.encodeToString(message)
            .also {
                logger.log(
                    Logger.Severity.INFO,
                    "Encoded message: $it",
                    null,
                )
            } + RECORD_SEPARATOR
        text.encodeToByteArray()
    }
}
