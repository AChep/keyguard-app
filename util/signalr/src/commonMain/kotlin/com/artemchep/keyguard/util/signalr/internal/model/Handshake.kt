package com.artemchep.keyguard.util.signalr.internal.model

import kotlinx.serialization.Serializable

@Serializable
internal data class Handshake(
    val protocol: String,
    val version: Int,
)
