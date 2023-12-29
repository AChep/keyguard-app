package com.artemchep.keyguard.common.model

data class DSecretDuplicateGroup(
    val id: String,
    val accuracy: Float,
    val ciphers: List<DSecret>,
)
