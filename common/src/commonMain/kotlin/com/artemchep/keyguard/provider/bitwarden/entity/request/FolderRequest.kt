package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FolderRequest(
    @SerialName("name")
    val name: String,
)

fun FolderRequest.Companion.of(
    model: BitwardenFolder,
) = kotlin.run {
    FolderRequest(
        name = model.name,
    )
}
