package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.TrashCipherByFolderId
import com.artemchep.keyguard.common.usecase.TrashCipherById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class TrashCipherByFolderIdImpl(
    private val getCiphers: GetCiphers,
    private val trashCipherById: TrashCipherById,
) : TrashCipherByFolderId {
    companion object {
        private const val TAG = "TrashCipherByFolderId.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        getCiphers = directDI.instance(),
        trashCipherById = directDI.instance(),
    )

    override fun invoke(
        folderIds: Set<String?>,
    ): IO<Unit> = performTrashCipherByFolderId(
        folderIds = folderIds,
    ).map { Unit }

    private fun performTrashCipherByFolderId(
        folderIds: Set<String?>,
    ) = getCiphers()
        .toIO()
        .effectMap { ciphers ->
            ciphers
                .asSequence()
                .filter { it.folderId in folderIds }
                .map { it.id }
                .toSet()
        }
        .flatMap { cipherIds ->
            trashCipherById(cipherIds)
        }
}
