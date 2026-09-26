package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.EditWordlistRequest
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistRepository
import com.artemchep.keyguard.common.usecase.EditWordlist

class EditWordlistImpl(
    private val generatorWordlistRepository: GeneratorWordlistRepository,
) : EditWordlist {
    override fun invoke(
        model: EditWordlistRequest,
    ) = ioEffect {
        val name = model.name
        generatorWordlistRepository
            .patch(
                id = model.id,
                name = name,
            )
            .bind()
    }
}
