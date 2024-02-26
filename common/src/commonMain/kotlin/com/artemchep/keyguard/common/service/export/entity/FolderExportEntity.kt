package com.artemchep.keyguard.common.service.export.entity

import kotlinx.serialization.Serializable

@Serializable
data class FolderExportEntity(
    val id: String,
    val name: String,
)
