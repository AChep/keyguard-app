package com.artemchep.keyguard.common.service.export.entity

import kotlinx.serialization.Serializable

@Serializable
data class ItemSshKeyExportEntity(
    val privateKey: String? = null,
    val publicKey: String? = null,
    val keyFingerprint: String? = null,
)
