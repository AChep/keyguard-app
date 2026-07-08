package com.artemchep.keyguard.common.service.agent

internal interface AgentPacketChannel {
    fun readPacket(): ByteArray?

    fun writePacket(
        packet: ByteArray,
    )
}
