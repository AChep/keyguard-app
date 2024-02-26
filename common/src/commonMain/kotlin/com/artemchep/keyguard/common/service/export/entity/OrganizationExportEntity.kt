package com.artemchep.keyguard.common.service.export.entity

import kotlinx.serialization.Serializable

@Serializable
data class OrganizationExportEntity(
    val id: String,
    val name: String,
)
