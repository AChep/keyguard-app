package com.artemchep.keyguard.common.service.export.entity

import kotlinx.serialization.Serializable

@Serializable
data class ItemCardExportEntity(
    val cardholderName: String? = null,
    val brand: String? = null,
    val number: String? = null,
    val expMonth: String? = null,
    val expYear: String? = null,
    val code: String? = null,
)
