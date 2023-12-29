package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder

sealed interface FolderUpdate {
    companion object;

    val source: BitwardenFolder

    data class Modify(
        override val source: BitwardenFolder,
        val folderId: String,
        val folderRequest: FolderRequest,
    ) : FolderUpdate

    data class Create(
        override val source: BitwardenFolder,
        val folderRequest: FolderRequest,
    ) : FolderUpdate
}

fun FolderUpdate.Companion.of(
    model: BitwardenFolder,
) = when {
    model.service.remote != null -> kotlin.run {
        FolderUpdate.Modify(
            source = model,
            folderId = model.service.remote.id,
            folderRequest = FolderRequest.of(
                model = model,
            ),
        )
    }

    else -> {
        FolderUpdate.Create(
            source = model,
            folderRequest = FolderRequest.of(
                model = model,
            ),
        )
    }
}
