package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.android.downloader.journal.GeneratorHistoryRepository
import com.artemchep.keyguard.common.usecase.GetGeneratorHistory
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetGeneratorHistoryImpl(
    generatorHistoryRepository: GeneratorHistoryRepository,
) : GetGeneratorHistory {
    private val sharedFlow = generatorHistoryRepository.get()

    constructor(directDI: DirectDI) : this(
        generatorHistoryRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
