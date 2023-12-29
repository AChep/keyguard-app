package com.artemchep.keyguard.feature.confirmation.organization

import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.serialization.Serializable

@Serializable
sealed interface FolderInfo : LeParcelable {
    @Serializable
    @LeParcelize
    data object None : FolderInfo

    @Serializable
    @LeParcelize
    data class New(
        val name: String,
    ) : FolderInfo

    @Serializable
    @LeParcelize
    data class Id(
        val id: String,
    ) : FolderInfo
}

fun FolderInfo.type() = when (this) {
    is FolderInfo.None -> FolderInfoType.None
    is FolderInfo.New -> FolderInfoType.New
    is FolderInfo.Id -> FolderInfoType.Id
}

enum class FolderInfoType {
    None,
    New,
    Id,
}
