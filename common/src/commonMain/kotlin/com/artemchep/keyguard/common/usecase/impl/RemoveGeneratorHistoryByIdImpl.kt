package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.android.downloader.journal.GeneratorHistoryRepository
import com.artemchep.keyguard.common.usecase.RemoveGeneratorHistoryById
import org.kodein.di.DirectDI
import org.kodein.di.instance

class RemoveGeneratorHistoryByIdImpl(
    private val generatorHistoryRepository: GeneratorHistoryRepository,
) : RemoveGeneratorHistoryById {
    constructor(directDI: DirectDI) : this(
        generatorHistoryRepository = directDI.instance(),
    )

    override fun invoke(ids: Set<String>) = generatorHistoryRepository
        .removeByIds(ids)
}
