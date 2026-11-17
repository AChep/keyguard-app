package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.WordlistWordException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.EditWordlistWordsRequest
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistRepository
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistWordRepository
import com.artemchep.keyguard.common.usecase.EditWordlistWords

class EditWordlistWordsImpl(
    private val generatorWordlistRepository: GeneratorWordlistRepository,
    private val generatorWordlistWordRepository: GeneratorWordlistWordRepository,
) : EditWordlistWords {
    override fun invoke(
        model: EditWordlistWordsRequest,
    ) = ioEffect {
        val add = model.add.distinct()
        // Only the added words matter: the list may be large.
        val existing = generatorWordlistWordRepository
            .getExistingWords(
                wordlistId = model.wordlistId,
                words = add,
            )
            .bind()
        val error = ReadWordlistUtil.patchErrorOrNull(
            existing = existing,
            remove = model.remove,
            add = model.add,
        )
        if (error != null) {
            throw WordlistWordException(error)
        }

        // A word that is removed and added back stays.
        val remove = model.remove - add.toSet()
        val addNew = add.filter { it !in existing }
        if (remove.isEmpty() && addNew.isEmpty()) {
            return@ioEffect
        }
        generatorWordlistRepository
            .patchWords(
                id = model.wordlistId,
                remove = remove,
                add = addNew,
            )
            .bind()
    }
}
