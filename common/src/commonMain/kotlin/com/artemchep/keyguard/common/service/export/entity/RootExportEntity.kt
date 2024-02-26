package com.artemchep.keyguard.common.service.export.entity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class RootExportEntity(
    val encrypted: Boolean,
    val organizations: List<OrganizationExportEntity> = emptyList(),
    val collections: List<CollectionExportEntity> = emptyList(),
    val folders: List<FolderExportEntity> = emptyList(),
    val items: List<JsonObject>,
)
