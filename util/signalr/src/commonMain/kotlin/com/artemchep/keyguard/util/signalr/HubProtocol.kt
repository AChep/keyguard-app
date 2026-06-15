package com.artemchep.keyguard.util.signalr

enum class TransferFormat {
    Text,
    Binary,
}

interface HubProtocol {
    val name: String
    val version: Int
    val transferFormat: TransferFormat

    fun parseMessages(
        payload: ByteArray,
    ): List<HubMessage>

    fun writeMessage(
        message: HubMessage,
    ): ByteArray
}
