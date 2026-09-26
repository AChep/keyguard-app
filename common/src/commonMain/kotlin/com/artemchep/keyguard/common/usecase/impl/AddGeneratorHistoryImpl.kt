package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.android.downloader.journal.GeneratorHistoryRepository
import com.artemchep.keyguard.common.model.DGeneratorHistory
import com.artemchep.keyguard.common.usecase.AddGeneratorHistory

class AddGeneratorHistoryImpl(
    private val generatorHistoryRepository: GeneratorHistoryRepository,
) : AddGeneratorHistory {
    override fun invoke(model: DGeneratorHistory) = generatorHistoryRepository
        .put(model)
}
