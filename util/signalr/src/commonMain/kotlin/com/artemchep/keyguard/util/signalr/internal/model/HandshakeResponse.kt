package com.artemchep.keyguard.util.signalr.internal.model

import kotlinx.serialization.Serializable

@Serializable
internal data class HandshakeResponse(
    val error: String? = null,
)
