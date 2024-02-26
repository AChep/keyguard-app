package com.artemchep.keyguard.common.service.export.entity

import com.artemchep.keyguard.provider.bitwarden.entity.FieldTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.LinkedIdTypeEntity
import kotlinx.serialization.Serializable

@Serializable
data class ItemFieldExportEntity(
    val type: FieldTypeEntity,
    val name: String? = null,
    val value: String? = null,
    val linkedId: LinkedIdTypeEntity? = null,
)
