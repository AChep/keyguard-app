package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.usecase.RenameFolderById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.name
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyFolderById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RenameFolderByIdImpl(
    private val modifyFolderById: ModifyFolderById,
) : RenameFolderById {
    companion object {
        private const val TAG = "RenameFolderById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyFolderById = directDI.instance(),
    )

    override fun invoke(
        folderIdsToNames: Map<String, String>,
    ): IO<Unit> = performRenameFolder(
        folderIdsToNames = folderIdsToNames,
    )

    private fun performRenameFolder(
        folderIdsToNames: Map<String, String>,
    ) = modifyFolderById(
        folderIdsToNames
            .keys,
    ) { model ->
        val newName = folderIdsToNames.getValue(model.folderId)

        var new = model
        new = new.copy(
            data_ = BitwardenFolder.name.set(new.data_, newName),
        )
        new
    }
}
