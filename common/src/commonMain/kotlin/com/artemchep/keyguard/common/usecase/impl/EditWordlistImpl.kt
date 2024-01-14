package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.EditWordlistRequest
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistRepository
import com.artemchep.keyguard.common.usecase.EditWordlist
import org.kodein.di.DirectDI
import org.kodein.di.instance

class EditWordlistImpl(
    private val generatorWordlistRepository: GeneratorWordlistRepository,
) : EditWordlist {
    constructor(directDI: DirectDI) : this(
        generatorWordlistRepository = directDI.instance(),
    )

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
