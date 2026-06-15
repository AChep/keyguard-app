package com.artemchep.keyguard.util.signalr.internal

import kotlinx.coroutines.flow.Flow

internal interface Transport {
    suspend fun send(
        message: ByteArray,
    )

    suspend fun sendText(
        message: String,
    )

    fun receive(
    ): Flow<ByteArray>

    suspend fun stop()
}
