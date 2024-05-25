package com.artemchep.keyguard.common.model

data class AddPasskeyCipherRequest(
    val cipherId: String,
    val data: Data,
) {
    data class Data(
        val credentialId: String?,
        val keyType: String, // public-key
        val keyAlgorithm: String, // ECDSA
        val keyCurve: String, // P-256
        val keyValue: String,
        val rpId: String,
        val rpName: String?,
        val counter: Int?,
        val userHandle: String,
        val userName: String?,
        val userDisplayName: String?,
        val discoverable: Boolean,
    )
}
