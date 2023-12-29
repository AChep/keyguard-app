package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.usecase.RemoveFolderById
import com.artemchep.keyguard.common.usecase.TrashCipherByFolderId
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.deleted
import com.artemchep.keyguard.core.store.bitwarden.service
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyFolderById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemoveFolderByIdImpl(
    private val modifyFolderById: ModifyFolderById,
    private val trashCipherByFolderId: TrashCipherByFolderId,
) : RemoveFolderById {
    companion object {
        private const val TAG = "RemoveFolderById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyFolderById = directDI.instance(),
        trashCipherByFolderId = directDI.instance(),
    )

    override fun invoke(
        folderIds: Set<String>,
        onCiphersConflict: RemoveFolderById.OnCiphersConflict,
    ): IO<Unit> = performRemoveFolder(
        folderIds = folderIds,
        onCiphersConflict = onCiphersConflict,
    ).flatMap {
        when (onCiphersConflict) {
            RemoveFolderById.OnCiphersConflict.TRASH -> performTrashCiphersByFolderId(folderIds)
            RemoveFolderById.OnCiphersConflict.IGNORE -> ioUnit()
        }
    }

    private fun performRemoveFolder(
        folderIds: Set<String>,
        onCiphersConflict: RemoveFolderById.OnCiphersConflict,
    ) = modifyFolderById(
        folderIds,
    ) { model ->
        var new = model
        new = new.copy(
            data_ = BitwardenFolder.service.deleted.set(new.data_, true),
        )
        new
    }

    private fun performTrashCiphersByFolderId(
        folderIds: Set<String>,
    ) = trashCipherByFolderId(folderIds)
}
