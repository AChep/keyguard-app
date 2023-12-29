package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.FolderOwnership2
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.MoveCipherToFolderById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.nullableFolderId
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class MoveCipherToFolderByIdImpl(
    private val modifyCipherById: ModifyCipherById,
    private val addFolder: AddFolder,
) : MoveCipherToFolderById {
    companion object {
        private const val TAG = "MoveCipherToFolderById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
        addFolder = directDI.instance(),
    )

    override fun invoke(
        cipherIds: Set<String>,
        ownership: FolderOwnership2,
    ): IO<Unit> = ioEffect {
        val folderId = when (val folder = ownership.folder) {
            is FolderInfo.None -> null
            is FolderInfo.New -> {
                val accountId = AccountId(ownership.accountId)
                val rq = mapOf(
                    accountId to folder.name,
                )
                val rs = addFolder(rq)
                    .bind()
                rs.first()
            }

            is FolderInfo.Id -> folder.id
        }
        performSetFolder(
            cipherIds = cipherIds,
            folderId = folderId,
        ).map { Unit }.bind()
    }

    private fun performSetFolder(
        cipherIds: Set<String>,
        folderId: String?,
    ) = modifyCipherById(
        cipherIds,
    ) { model ->
        var new = model
        new = new.copy(
            data_ = BitwardenCipher.nullableFolderId.set(new.data_, folderId),
        )
        new
    }
}
