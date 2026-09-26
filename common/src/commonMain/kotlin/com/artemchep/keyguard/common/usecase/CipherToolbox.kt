package com.artemchep.keyguard.common.usecase

interface CipherToolbox {
    val favouriteCipherById: FavouriteCipherById
    val rePromptCipherById: RePromptCipherById
    val changeCipherNameById: ChangeCipherNameById
    val changeCipherTagsById: ChangeCipherTagsById
    val changeCipherPasswordById: ChangeCipherPasswordById
    val copyCipherById: CopyCipherById
    val moveCipherToFolderById: MoveCipherToFolderById
    val patchWatchtowerAlertCipher: PatchWatchtowerAlertCipher
    val restoreCipherById: RestoreCipherById
    val trashCipherById: TrashCipherById
    val unarchiveCipherById: UnarchiveCipherById
    val archiveCipherById: ArchiveCipherById
    val removeCipherById: RemoveCipherById
    val cipherMerge: CipherMerge
}

class CipherToolboxImpl(
    override val favouriteCipherById: FavouriteCipherById,
    override val rePromptCipherById: RePromptCipherById,
    override val changeCipherNameById: ChangeCipherNameById,
    override val changeCipherTagsById: ChangeCipherTagsById,
    override val changeCipherPasswordById: ChangeCipherPasswordById,
    override val copyCipherById: CopyCipherById,
    override val moveCipherToFolderById: MoveCipherToFolderById,
    override val patchWatchtowerAlertCipher: PatchWatchtowerAlertCipher,
    override val restoreCipherById: RestoreCipherById,
    override val trashCipherById: TrashCipherById,
    override val unarchiveCipherById: UnarchiveCipherById,
    override val archiveCipherById: ArchiveCipherById,
    override val removeCipherById: RemoveCipherById,
    override val cipherMerge: CipherMerge,
) : CipherToolbox
