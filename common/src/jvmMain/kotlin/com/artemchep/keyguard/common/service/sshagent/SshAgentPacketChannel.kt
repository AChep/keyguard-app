package com.artemchep.keyguard.common.service.sshagent

internal interface SshAgentPacketChannel {
    fun readPacket(): ByteArray?

    fun writePacket(
        packet: ByteArray,
    )
}
