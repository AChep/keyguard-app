package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistRepository
import com.artemchep.keyguard.common.usecase.RemoveWordlistById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemoveWordlistByIdImpl(
    private val generatorWordlistRepository: GeneratorWordlistRepository,
) : RemoveWordlistById {
    constructor(directDI: DirectDI) : this(
        generatorWordlistRepository = directDI.instance(),
    )

    override fun invoke(
        wordlistIds: Set<Long>,
    ): IO<Unit> = generatorWordlistRepository
        .removeByIds(wordlistIds)
}
