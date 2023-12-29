package com.artemchep.keyguard.common.usecase

import org.kodein.di.DirectDI
import org.kodein.di.instance

interface CipherToolbox {
    val favouriteCipherById: FavouriteCipherById
    val rePromptCipherById: RePromptCipherById
    val changeCipherNameById: ChangeCipherNameById
    val changeCipherPasswordById: ChangeCipherPasswordById
    val copyCipherById: CopyCipherById
    val moveCipherToFolderById: MoveCipherToFolderById
    val restoreCipherById: RestoreCipherById
    val trashCipherById: TrashCipherById
    val removeCipherById: RemoveCipherById
    val cipherMerge: CipherMerge
}

class CipherToolboxImpl(
    override val favouriteCipherById: FavouriteCipherById,
    override val rePromptCipherById: RePromptCipherById,
    override val changeCipherNameById: ChangeCipherNameById,
    override val changeCipherPasswordById: ChangeCipherPasswordById,
    override val copyCipherById: CopyCipherById,
    override val moveCipherToFolderById: MoveCipherToFolderById,
    override val restoreCipherById: RestoreCipherById,
    override val trashCipherById: TrashCipherById,
    override val removeCipherById: RemoveCipherById,
    override val cipherMerge: CipherMerge,
) : CipherToolbox {
    constructor(directDI: DirectDI) : this(
        favouriteCipherById = directDI.instance(),
        rePromptCipherById = directDI.instance(),
        changeCipherNameById = directDI.instance(),
        changeCipherPasswordById = directDI.instance(),
        copyCipherById = directDI.instance(),
        moveCipherToFolderById = directDI.instance(),
        restoreCipherById = directDI.instance(),
        trashCipherById = directDI.instance(),
        removeCipherById = directDI.instance(),
        cipherMerge = directDI.instance(),
    )
}
