package com.artemchep.keyguard.common.service.gpgagent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

@OptIn(ExperimentalSerializationApi::class)
internal object GpgAgentProtoCodec {
    private val protoBuf = ProtoBuf

    fun decodeRequest(
        packet: ByteArray,
    ): GpgAgentMessages.IpcRequest = protoBuf.decodeFromByteArray(packet)

    fun encodeResponse(
        response: GpgAgentMessages.IpcResponse,
    ): ByteArray = protoBuf.encodeToByteArray(response)
}
