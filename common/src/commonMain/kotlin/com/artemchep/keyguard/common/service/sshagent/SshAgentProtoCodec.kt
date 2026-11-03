package com.artemchep.keyguard.common.service.sshagent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

@OptIn(ExperimentalSerializationApi::class)
internal object SshAgentProtoCodec {
    private val protoBuf = ProtoBuf

    fun encodeRequest(
        request: SshAgentMessages.IpcRequest,
    ): ByteArray = protoBuf.encodeToByteArray(request)

    fun decodeRequest(
        packet: ByteArray,
    ): SshAgentMessages.IpcRequest = protoBuf.decodeFromByteArray(packet)

    fun encodeResponse(
        response: SshAgentMessages.IpcResponse,
    ): ByteArray = protoBuf.encodeToByteArray(response)

    fun decodeResponse(
        packet: ByteArray,
    ): SshAgentMessages.IpcResponse = protoBuf.decodeFromByteArray(packet)
}
