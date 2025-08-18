package com.artemchep.keyguard.common.model

data class DSecretDuplicateGroup(
    val id: String,
    val accuracy: Float,
    val summary: String?,
    val ciphers: List<DSecret>,
)
