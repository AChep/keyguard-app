package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.usecase.MergeFolderById
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyFolderById

/**
 * @author Artem Chepurnyi
 */
class MergeFolderByIdImpl(
    private val modifyFolderById: ModifyFolderById,
) : MergeFolderById {
    companion object {
        private const val TAG = "MergeFolderById.bitwarden"
    }

    override fun invoke(
        folderIds: Set<String>,
        folderName: String,
    ): IO<Unit> = ioUnit()
}
