package com.artemchep.keyguard.common.service.export.entity

import kotlinx.serialization.Serializable

@Serializable
data class CollectionExportEntity(
    val id: String,
    val name: String,
    val organizationId: String?,
    val externalId: String?,
)
