package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.android.downloader.journal.GeneratorHistoryRepository
import com.artemchep.keyguard.common.model.DGeneratorHistory
import com.artemchep.keyguard.common.usecase.AddGeneratorHistory
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddGeneratorHistoryImpl(
    private val generatorHistoryRepository: GeneratorHistoryRepository,
) : AddGeneratorHistory {
    constructor(directDI: DirectDI) : this(
        generatorHistoryRepository = directDI.instance(),
    )

    override fun invoke(model: DGeneratorHistory) = generatorHistoryRepository
        .put(model)
}
